package com.softwire.todos;

import com.atlassian.jira.rest.client.domain.BasicResolution;
import com.atlassian.jira.rest.client.domain.Issue;
import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * CLI entry point to the `TodoChecker` tool.
 */
public class TodoCheckerMain
        implements JiraClient.Config, JiraCommenter.Config, SourceControlLinkFormatter.Config {

    /// Command-line arguments

    @Option(name = "--write-to-jira",
            usage = "Unless this is set, no changes will be made in JIRA")
    public boolean writeToJira = false;

    /**
     * If this is set, only the specified card will be acted on
     */
    @Option(name = "--only-card",
            usage = "Set this to a JIRA card id to only operate on that one card.")
    public String restrictToSingleCardId = null;

    @Option(name = "--src",
            usage = "The directory to scan for TODOs (must be in a git checkout)",
            required = true)
    public String srcDir;

    @Option(name = "--jira-url",
            usage = "The base url for jira with trailing slash, defaults to https://jira.softwire.com/jira/",
            required = false)
    public String jiraUrl = "https://jira.softwire.com/jira/";

    @Option(name = "--jira-project-key",
            usage = "The project key for JIRA, e.g. AAA, INTRO, PROJECTX, etc.  Pass this flag multiple times for " +
                    "multiple projects.  If you need to use a regex other than the project key when looking for the " +
                    "card key in a todo comment, then pass it here with an \"=\". For example if your JIRA project " +
                    "key is something long like COMPANY-DEPT-FOO but your team writes TODOs like " +
                    "\"TODO:FOO-123\", then pass \"--jira-project COMPANY-DEPT-FOO=FOO\"",
            required = true,
            handler = JiraProjectOptionHandler.class)
    public List<JiraProject> jiraProjects;

    @Option(name = "--github-url",
            usage = "The url of the project in github, e.g. https://github.com/softwire/todo-checker",
            forbids = "--gitblit-url")
    public String githubUrl;

    @Option(name = "--gitblit-url",
            usage = "The url of the project in gitblit, e.g. https://example.com/gitblit?r=todo-checker.git",
            forbids = "--github-url")
    public String gitblitUrl;

    @Option(name = "--jira-username",
            usage = "The username of the Jira user who will comment on Jira tickets, e.g. sjw",
            required = true)
    public String jiraUsername;

    @Option(name = "--jira-password",
            usage = "The password of the Jira user who will comment on Jira tickets",
            required = true)
    public String jiraPassword;

    @Option(name = "--exclude-path-regex",
            usage = "Any paths to exclude, by regex, e.g. '^(node_modules/|broken-code/)'",
            required = false)
    public String excludePathRegex;

    @Option(name = "--job-name",
            usage = "Job name.  This will be prefixed to all JIRA comments.  You must set this to a unique value if " +
                    "you have multiple jobs running against different codebases but with the same JIRA project, " +
                    "otherwise the jobs will interfere with each other.")
    public String jobName = null;

    @Option(name = "--report-file",
            usage = "Write report of errors to this file as well as to the console.")
    public String reportFile = null;
    /// End config

    private JiraClient jiraClient;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final StringBuilder errorReport = new StringBuilder();

    public TodoCheckerMain() throws URISyntaxException {
    }

    public static void main(String[] args) throws Exception {
        TodoCheckerMain app = new TodoCheckerMain();
        CmdLineParser parser = new CmdLineParser(app);
        boolean success;
        try {
            parser.parseArgument(args);
            success = app.run();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            success = false;
        }

        System.exit(success ? 0 : 1);
    }

    private boolean run() throws Exception {
        this.jiraClient = new JiraClient(this);

        if (!writeToJira) {
            log.info("This script will not write to JIRA unless you pass '--write-to-jira' " +
                    "as a command-line argument");
        }

        File srcDirFile = new File(this.srcDir);
        checkArgument(srcDirFile.isDirectory(), "Invalid --src argument");

        List<CodeTodo> allTodos = new TodoFinder(srcDirFile).findAllTodosInSource(this.excludePathRegex);

        log.info("{} code TODOs found:\n{}",
                allTodos.size(),
                Joiner.on("\n").join(allTodos));

        Multimap<Issue, CodeTodo> todosByIssue = groupTodosByJiraIssue(allTodos);

        new JiraCommenter(
                this,
                jiraClient,
                new SourceControlLinkFormatter(this)).updateJiraComments(todosByIssue);

        boolean success = findTodosOnClosedCards(todosByIssue);
        success &= findTodosWithoutACardNumber(todosByIssue);

        if (reportFile != null) {
            Files.write(Paths.get(reportFile), errorReport.toString().getBytes(StandardCharsets.UTF_8));
        }

        return success;
    }

    /**
     * Sort the list into a multimap from JiraCard to CodeTodos.
     * Any CodeTodos with no card will be included at the "null" key.
     */
    private Multimap<Issue, CodeTodo> groupTodosByJiraIssue(List<CodeTodo> allTodos) throws Exception {
        HashMultimap<Issue, CodeTodo> acc = HashMultimap.create();

        List<Pattern> jiraProjectPatterns = new ArrayList<Pattern>();
        for (JiraProject jiraProject: jiraProjects) {
            jiraProjectPatterns.add(Pattern.compile(
                    jiraProject.getRegex() + "[-_:]([0-9]+)",
                    Pattern.CASE_INSENSITIVE));
        }

        for (CodeTodo codeTodo : allTodos) {
            String id = null;
            for (int i = 0; i < jiraProjects.size(); i++) {
                Matcher matcher = jiraProjectPatterns.get(i).matcher(codeTodo.getLine());
                if (matcher.find()) {
                    id = jiraProjects.get(i).getKey() + "-" + matcher.group(1);
                    break;
                }
            }

            if (id != null) {
                if (null == restrictToSingleCardId || id.equals(restrictToSingleCardId)) {
                    Issue issue = jiraClient.getIssue(id);
                    acc.put(issue, codeTodo);
                }
            } else {
                if (null == restrictToSingleCardId) {
                    acc.put(null, codeTodo);
                }
            }
        }

        return acc;
    }

    private boolean findTodosOnClosedCards(Multimap<Issue, CodeTodo> todosByIssue) {
        boolean ok = true;
        for (Map.Entry<Issue, Collection<CodeTodo>> entry : todosByIssue.asMap().entrySet()) {
            Issue issue = entry.getKey();
            if (issue == null) {
                continue;
            }

            BasicResolution resolution = issue.getResolution();
            if (resolution != null) {
                logAndReportError("TODOs on a resolved '%s' JIRA card found %s",
                        resolution.getName(),
                        issue.getKey());
                for (CodeTodo codeTodo : entry.getValue()) {
                    logAndReportError("  %s:%s %s",
                            codeTodo.getFile(),
                            codeTodo.getLineNumber(),
                            codeTodo.getLine());
                }
                ok = false;
            }

            switch (issue.getStatus().getName()) {
                case "In Test":
                case "Passed test":
                case "UAT":
                case "Done":
                    logAndReportError("TODOs on a JIRA card with status '%s': %s",
                            issue.getStatus().getName(),
                            issue.getKey());
                    for (CodeTodo codeTodo : entry.getValue()) {
                        logAndReportError("  %s:%s %s",
                                codeTodo.getFile(),
                                codeTodo.getLineNumber(),
                                codeTodo.getLine());
                    }
                    ok = false;
            }
        }
        return ok;
    }

    private boolean findTodosWithoutACardNumber(Multimap<Issue, CodeTodo> todosByIssue) {
        Collection<CodeTodo> codeTodos = todosByIssue.get(null);
        if (codeTodos.isEmpty()) {
            return true;
        }
        logAndReportError("TODOs without a JIRA card found:");
        for (CodeTodo codeTodo : codeTodos) {
            logAndReportError("  %s:%s %s",
                    codeTodo.getFile(),
                    codeTodo.getLineNumber(),
                    codeTodo.getLine());
        }
        return false;
    }

    private void logAndReportError(String formatString, Object ...args) {
        String error = String.format(formatString, args);
        log.error(error);
        errorReport.append(error).append('\n');
    }

    @Override
    public String getJobName() {
        return jobName;
    }

    @Override
    public String getRestrictToSingleCardId() {
        return restrictToSingleCardId;
    }

    @Override
    public boolean getWriteToJira() {
        return writeToJira;
    }

    @Override
    public List<JiraProject> getJiraProjects() {
        return jiraProjects;
    }

    @Override
    public String getGithubUrl() {
        return githubUrl;
    }

    @Override
    public String getJiraUrl() {
        return jiraUrl;
    }

    @Override
    public String getGitblitUrl() {
        return gitblitUrl;
    }

    @Override
    public String getJiraUsername() {
        return jiraUsername;
    }

    @Override
    public String getJiraPassword() {
        return jiraPassword;
    }
}
