package edu.umd.cs.findbugs.cloud.appEngine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.GeneratedMessage;

import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugDesignation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.cloud.AbstractCloud;
import edu.umd.cs.findbugs.cloud.CloudPlugin;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Evaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetRecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Issue;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogIn;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogInResponse;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.RecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadEvaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadIssues;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Evaluation.Builder;
import edu.umd.cs.findbugs.cloud.username.AppEngineNameLookup;

public class AppEngineCloud extends AbstractCloud {

	private static final int EVALUATION_CHECK_SECS = 5*60;

	private Map<String, Issue> issuesByHash = new ConcurrentHashMap<String, Issue>();

	private  String host;
	private long sessionId;
	private String user;

	private Timer timer;

	private long mostRecentEvaluationMillis = 0;

	public AppEngineCloud(CloudPlugin plugin, BugCollection bugs) {
		super(plugin, bugs);
	}

	// ====================== initialization =====================

	public boolean availableForInitialization() {
		return true;
	}

	public boolean initialize() {
		AppEngineNameLookup lookerupper = new AppEngineNameLookup();
		if (!lookerupper.initialize(plugin, bugCollection)) {
			return false;
		}
		sessionId = lookerupper.getSessionId();
		user = lookerupper.getUsername();
		host = lookerupper.getHost();

		if (timer != null) timer.cancel();
		timer = new Timer("App Engine Cloud evaluation updater", true);
		int periodMillis = EVALUATION_CHECK_SECS*1000;
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				updateEvaluationsFromServer();
			}
		}, periodMillis, periodMillis);
		bugsPopulated();
		return true;
	}

	// =============== accessors ===================

	public String getUser() {
		return user;
	}

	public BugDesignation getPrimaryDesignation(BugInstance b) {
		Evaluation e = getMostRecentEvaluation(b);
		return e == null ? null : createBugDesignation(e);
	}

	public long getFirstSeen(BugInstance b) {
		Issue issue = issuesByHash.get(b.getInstanceHash());
		if (issue == null)
			return Long.MAX_VALUE;
		return issue.getFirstSeen();
	}

	@Override
	protected Iterable<BugDesignation> getAllUserDesignations(BugInstance bd) {
		List<BugDesignation> list = new ArrayList<BugDesignation>();
		for (Evaluation eval : issuesByHash.get(bd.getInstanceHash()).getEvaluationsList()) {
			list.add(createBugDesignation(eval));
		}
		return list;
	}

	// ================== mutators ================

	public void bugsPopulated() {
		Map<String, BugInstance> bugsByHash = new HashMap<String, BugInstance>();

		for(BugInstance b : bugCollection.getCollection()) {
			bugsByHash.put(b.getInstanceHash(), b);
		}

		// send all instance hashes to server
		try {
			LogInResponse response = submitHashes(bugsByHash);
			for (Issue issue : response.getFoundIssuesList()) {
				storeProtoIssue(issue);
				BugInstance bugInstance = bugsByHash.remove(issue.getHash());
				if (bugInstance != null) {
					updateBugInstanceAndNotify(bugInstance);
				}
			}
			Collection<BugInstance> newBugs = bugsByHash.values();
			if (!newBugs.isEmpty()) {
				uploadIssues(newBugs);
			}

		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public void bugFiled(BugInstance b, Object bugLink) {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("deprecation")
	public void storeUserAnnotation(BugInstance bugInstance) {
		BugDesignation designation = bugInstance.getNonnullUserDesignation();
		Builder evalBuilder = Evaluation.newBuilder()
				.setWhen(designation.getTimestamp())
				.setDesignation(designation.getDesignationKey());
		String comment = designation.getAnnotationText();
		if (comment != null) {
			evalBuilder.setComment(comment);
		}
		UploadEvaluation uploadMsg = UploadEvaluation.newBuilder()
				.setSessionId(sessionId)
				.setHash(bugInstance.getInstanceHash())
				.setEvaluation(evalBuilder.build())
				.build();

		openPostUrl(uploadMsg, "/upload-evaluation");
	}

	/** package-private for testing */
	void updateEvaluationsFromServer() {
		RecentEvaluations evals;
		try {
			evals = getRecentEvaluationsFromServer();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		for (Issue issue : evals.getIssuesList()) {
			Issue existingIssue = issuesByHash.get(issue.getHash());
			if (existingIssue != null) {
				Issue newIssue = mergeIssues(existingIssue, issue);
				assert newIssue.getHash().equals(issue.getHash());
				storeProtoIssue(newIssue);
				BugInstance bugInstance = getBugByHash(issue.getHash());
				if (bugInstance != null) {
					updateBugInstanceAndNotify(bugInstance);
				}
			}
		}
	}

	// ==================== for testing ===========================

	/** package-private for testing */
	void setSessionId(long id) {
		this.sessionId = id;
	}

	/** package-private for testing */
	void setUsername(String user) {
		this.user = user;
	}

	// ================== private methods ======================

	private BugDesignation createBugDesignation(Evaluation e) {
		return new BugDesignation(e.getDesignation(), e.getWhen(),
								  e.getComment(), e.getWho());
	}

	private void storeProtoIssue(Issue newIssue) {
		for (Evaluation eval : newIssue.getEvaluationsList()) {
			if (eval.getWhen() > mostRecentEvaluationMillis) {
				mostRecentEvaluationMillis = eval.getWhen();
			}
		}
		issuesByHash.put(newIssue.getHash(), newIssue);
	}

	private LogInResponse submitHashes(Map<String, BugInstance> bugsByHash)
			throws IOException, MalformedURLException {
		LogIn hashList = LogIn.newBuilder()
				//TODO: should the timestamp be converted to UTC?
				.setAnalysisTimestamp(bugCollection.getAnalysisTimestamp())
				.setSessionId(sessionId)
				.addAllMyIssueHashes(bugsByHash.keySet())
				.build();

		HttpURLConnection conn = openConnection("/log-in");
		conn.setDoOutput(true);
		conn.connect();
		OutputStream stream = conn.getOutputStream();
		hashList.writeTo(stream);
		stream.close();
		if (conn.getResponseCode() != 200) {
			throw new IOException("Response code " + conn.getResponseCode()
					+ " : " + conn.getResponseMessage());
		}
		LogInResponse response = LogInResponse.parseFrom(conn.getInputStream());
		conn.disconnect();
		return response;
	}

	/** package-private for testing */
	void uploadIssues(Collection<BugInstance> bugsToSend)
			throws MalformedURLException, IOException {
		UploadIssues.Builder issueList = UploadIssues.newBuilder();
		issueList.setSessionId(sessionId);
		for (BugInstance bug: bugsToSend) {
			issueList.addNewIssues(ProtoClasses.Issue.newBuilder()
					.setHash(bug.getInstanceHash())
					.setBugPattern(bug.getType())
					.setPriority(bug.getPriority())
					.setPrimaryClass(bug.getPrimaryClass().getClassName())
					.setFirstSeen(bug.getFirstVersion())
					.setLastSeen(bug.getLastVersion())
					.build());
		}
		openPostUrl(issueList.build(), "/upload-issues");

	}

	/** package-private for testing */
	HttpURLConnection openConnection(String url) throws IOException {
		URL u = new URL(host + url);
		return (HttpURLConnection) u.openConnection();
	}

	@SuppressWarnings("deprecation")
	private void updateBugInstanceAndNotify(BugInstance bugInstance) {
		BugDesignation primaryDesignation = getPrimaryDesignation(bugInstance);
		if (primaryDesignation != null) {
			bugInstance.setUserDesignation(primaryDesignation);
			updatedIssue(bugInstance);
		}
	}

	private Issue mergeIssues(Issue existingIssue, Issue updatedIssue) {
		List<Evaluation> allEvaluations = new ArrayList<Evaluation>();
		allEvaluations.addAll(existingIssue.getEvaluationsList());
		allEvaluations.addAll(updatedIssue.getEvaluationsList());
		removeAllButLatestEvaluationPerUser(allEvaluations);

		return Issue.newBuilder(existingIssue)
				.clearEvaluations()
				.addAllEvaluations(allEvaluations)
				.build();
	}

	private void removeAllButLatestEvaluationPerUser(List<Evaluation> allEvaluations) {
		Set<String> seenUsernames = new HashSet<String>();
		for (ListIterator<Evaluation> it = allEvaluations.listIterator(allEvaluations.size()); it.hasPrevious();) {
			Evaluation evaluation = it.previous();
			boolean isNewUsername = seenUsernames.add(evaluation.getWho());
			if (!isNewUsername)
				it.remove();
		}
	}

	private RecentEvaluations getRecentEvaluationsFromServer() throws IOException {
		HttpURLConnection conn = openConnection("/get-recent-evaluations");
		conn.setDoOutput(true);
		try {
			OutputStream outputStream = conn.getOutputStream();
			GetRecentEvaluations.newBuilder()
					.setSessionId(sessionId)
					.setTimestamp(mostRecentEvaluationMillis)
					.build()
					.writeTo(outputStream);
			outputStream.close();
			if (conn.getResponseCode() != 200) {
				throw new IllegalStateException(
						"server returned error code "
								+ conn.getResponseCode() + " "
								+ conn.getResponseMessage());
			}
			return RecentEvaluations.parseFrom(conn.getInputStream());
		} finally {
			conn.disconnect();
		}
	}

	private Evaluation getMostRecentEvaluation(BugInstance b) {
		Issue issue = issuesByHash.get(b.getInstanceHash());
		if (issue == null)
			return null;
		Evaluation mostRecent = null;
		long when = Long.MIN_VALUE;
		for(Evaluation e : issue.getEvaluationsList())
			if (e.getWho().equals(getUser()) && e.getWhen() > when) {
				mostRecent = e;
				when = e.getWhen();
			}

		return mostRecent;
	}

	private void openPostUrl(GeneratedMessage uploadMsg, String url) {
		try {
			HttpURLConnection conn = openConnection(url);
			conn.setDoOutput(true);
			conn.connect();
			try {
				if (uploadMsg != null) {
					OutputStream stream = conn.getOutputStream();
					uploadMsg.writeTo(stream);
					stream.close();
				}
				if (conn.getResponseCode() != 200) {
					throw new IllegalStateException(
							"server returned error code "
									+ conn.getResponseCode() + " "
									+ conn.getResponseMessage());
				}
			} finally {
				conn.disconnect();
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
