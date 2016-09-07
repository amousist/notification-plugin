/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tikal.hudson.plugins.notification;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.tikal.hudson.plugins.notification.model.BuildState;
import com.tikal.hudson.plugins.notification.model.JobState;
import com.tikal.hudson.plugins.notification.model.ScmState;
import hudson.EnvVars;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings({ "unchecked", "rawtypes" })
public enum Phase {
	STARTED, COMPLETED, FINALIZED;

	@SuppressWarnings("CastToConcreteClass")
	public void handle(Run run, TaskListener listener) {

		HudsonNotificationProperty property = (HudsonNotificationProperty) run.getParent()
				.getProperty(HudsonNotificationProperty.class);
		if (property == null) {
			return;
		}

		for (Endpoint target : property.getEndpoints()) {
			if (isRun(target)) {
				listener.getLogger().println(String.format("Notifying endpoint '%s'", target));

				try {
					JobState jobState = buildJobState(run.getParent(), run, listener, target);
					EnvVars environment = run.getEnvironment(listener);
					String expandedUrl = environment.expand(target.getUrl());
					target.getProtocol().send(expandedUrl, target.getFormat().serialize(jobState), target.getTimeout(),
							target.isJson());
				} catch (Throwable error) {
					error.printStackTrace(listener.error(String.format("Failed to notify endpoint '%s'", target)));
					listener.getLogger().println(String.format("Failed to notify endpoint '%s' - %s: %s", target,
							error.getClass().getName(), error.getMessage()));
				}
			}
		}
	}

	/**
	 * Determines if the endpoint specified should be notified at the current
	 * job phase.
	 */
	private boolean isRun(Endpoint endpoint) {
		String event = endpoint.getEvent();
		return ((event == null) || event.equals("all") || event.equals(this.toString().toLowerCase()));
	}

	private JobState buildJobState(Job job, Run run, TaskListener listener, Endpoint target)
			throws IOException, InterruptedException {

		Jenkins jenkins = Jenkins.getInstance();
		String rootUrl = jenkins.getRootUrl();
		JobState jobState = new JobState();
		BuildState buildState = new BuildState();
		ScmState scmState = new ScmState();
		scmState.setCulprits(new ArrayList<String>());
		Result result = run.getResult();
		ParametersAction paramsAction = run.getAction(ParametersAction.class);
		EnvVars environment = run.getEnvironment(listener);
		StringBuilder log = this.getLog(run, target);

		jobState.setName(job.getName());
		jobState.setUrl(job.getUrl());
		jobState.setBuild(buildState);

		buildState.setNumber(run.number);
		buildState.setQueueId(run.getQueueId());
		buildState.setUrl(run.getUrl());
		buildState.setPhase(this);
		buildState.setScm(scmState);
		buildState.setLog(log);

		if (result != null) {
			buildState.setStatus(result.toString());
		}

		if (rootUrl != null) {
			buildState.setFullUrl(rootUrl + run.getUrl());
		}

		buildState.updateArtifacts(job, run);

		if (paramsAction != null) {
			EnvVars env = new EnvVars();
			for (ParameterValue value : paramsAction.getParameters()) {
				if (!value.isSensitive()) {
					value.buildEnvironment(run, env);
				}
			}
			buildState.setParameters(env);
		}

		if (environment.get("GIT_URL") != null) {
			scmState.setUrl(environment.get("GIT_URL"));
		}

		if (environment.get("SVN_URL") != null) {
			scmState.setUrl(environment.get("SVN_URL"));
		}

		if (environment.get("GIT_BRANCH") != null) {
			scmState.setBranch(environment.get("GIT_BRANCH"));
		}

		if (environment.get("GIT_COMMIT") != null) {
			scmState.setCommit(environment.get("GIT_COMMIT"));
		}

		if (environment.get("SVN_REVISION") != null) {
			scmState.setCommit(environment.get("SVN_REVISION"));
		}
		
		for (User user : getCulprits(run)){
			scmState.getCulprits().add(user.getFullName());
			System.out.println(user.getFullName());
		}
		
		return jobState;
	}

	private Set<User> getCulprits(Run<?,?> run) {
		final HashSet<User> users = new HashSet<User>();
			if (run instanceof AbstractBuild<?, ?>) {
				final ChangeLogSet<?> changeLogSet = ((AbstractBuild<?, ?>) run).getChangeSet();
				if (changeLogSet != null) {
					addChangeSetUsers(changeLogSet, users);
			} else {
				try {
					Method getChangeSets = run.getClass().getMethod("getChangeSets");
					if (List.class.isAssignableFrom(getChangeSets.getReturnType())) {
						@SuppressWarnings("unchecked")
						List<ChangeLogSet<ChangeLogSet.Entry>> sets = (List<ChangeLogSet<ChangeLogSet.Entry>>) getChangeSets
								.invoke(run);
						if (Iterables.all(sets, Predicates.instanceOf(ChangeLogSet.class))) {
							for (ChangeLogSet<ChangeLogSet.Entry> set : sets) {
								addChangeSetUsers(set, users);
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return users;
	}

	private static void addChangeSetUsers(ChangeLogSet<?> changeLogSet, Set<User> users) {
		final Set<User> changeAuthors = new HashSet<User>();
		for (final ChangeLogSet.Entry change : changeLogSet) {
			final User changeAuthor = change.getAuthor();
			changeAuthors.add(changeAuthor);
		}
		users.addAll(changeAuthors);
	}

	private StringBuilder getLog(Run run, Endpoint target) {
		StringBuilder log = new StringBuilder("");
		Integer loglines = target.getLoglines();

		if (null == loglines) {
			return log;
		}

		try {
			switch (loglines) {
			// The full log
			case -1:
				log.append(run.getLog());
				break;
			default:
				List<String> logEntries = run.getLog(loglines);
				for (String entry : logEntries) {
					log.append(entry);
					log.append("\n");
				}
			}
		} catch (IOException e) {
			log.append("Unable to retrieve log");
		}
		return log;
	}
}
