package it.com.pbaranchikov.stash.checks;

import it.com.pbaranchikov.stash.checks.utils.Project;
import it.com.pbaranchikov.stash.checks.utils.Repository;
import it.com.pbaranchikov.stash.checks.utils.Workspace;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Before;

import com.google.common.io.Files;
import com.pbaranchikov.stash.checks.Constants;

/**
 * Abstract suitcase for git work copies.<br/>
 * This code is rather dirty and should be rewritten since Atlassian fix several
 * issues with integration tests.
 * @see STASH-5523
 * @see STASH-4616
 * @author Pavel Baranchikov
 */
public abstract class AbstractGitCheck {

    protected static final String WRONG_CONTENTS = "This\r\nis file with wrong contents\r\n";
    protected static final String GOOD_CONTENTS = "This\nis file with wrong contents\n";
    protected static final String GOOD_FILE = "goodFile";
    protected static final String BAD_FILE = "badFile";
    protected static final String BRANCH_MASTER = "master";

    private static final String STASH_REST_PATH = "/rest/api/1.0";
    private static final String REST_PROJECTS = "/projects";
    private static final String REST_REPOSITORIES = "/repos";
    private static final String REST_DELIMITER = "/";
    private static final String BLOCK_QUOTE = "\"";
    private static final String NAME = "name";
    private static final String KEY = "key";
    private static final String STASH_USER = "admin";
    private static final String STASH_PASSWORD = STASH_USER;
    private static final String HOOK_KEY = "com.pbaranchikov.stash-eol-check:stash-check-eol-hook";

    private static final String PROJECT_KEY = "PROJECT_FOR_TESTS";
    private static final String REPOSITORY_KEY = "TEST_REPOSITORY";

    private static final String RANDOM_FILE = "randomFile";

    private static final String GIT_BRANCH = "branch";
    private static final String GIT_TAG = "tag";
    private static final String GIT_PUSH = "push";
    private static final String GIT_ORIGIN = "origin";
    private static final String GIT_M = "-m";
    private static final String GIT = "git";
    private static final String DEL_PREFIX = ":";

    private static final String EOL = "\n";

    private static final String PROP_INSTANCE_URL = "baseurl.stash";

    private final HttpClient httpClient;
    private final HttpClientContext httpContext;

    private Repository repository;
    private Project project;
    private Workspace workspace;
    private int fileCounter;
    private final URL stashUrl;

    protected AbstractGitCheck() {
        final String instanceUrl = System.getProperty(PROP_INSTANCE_URL);
        if (instanceUrl == null || instanceUrl.isEmpty()) {
            throw new IllegalArgumentException(PROP_INSTANCE_URL + " system property is to be set");
        }
        try {
            stashUrl = new URL(instanceUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        final CredentialsProvider provider = new BasicCredentialsProvider();
        final UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(STASH_USER,
                STASH_PASSWORD);
        provider.setCredentials(AuthScope.ANY, credentials);

        final AuthCache authCache = new BasicAuthCache();
        final HttpHost host = new HttpHost(stashUrl.getHost(), stashUrl.getPort());
        authCache.put(host, new BasicScheme());

        httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();

        httpContext = HttpClientContext.create();
        httpContext.setCredentialsProvider(provider);
        httpContext.setAuthCache(authCache);
    }

    @Before
    public void createInitialConfig() throws Exception {
        fileCounter = 0;
        project = createProject(PROJECT_KEY);
        repository = project.forceCreateRepository(REPOSITORY_KEY);
        repository.setHookSettings("", false);
        workspace = repository.cloneRepository();
        workspace.setCrlf("false");
        workspace.config("push.default", "simple");
    }

    @After
    public void sweepWorkspace() throws Exception {
        repository.delete();
        project.delete();
        FileUtils.forceDelete(workspace.getWorkDir());
    }

    protected Project createProject(String projectKey) {

        final Project oldProject = new RestProject(PROJECT_KEY);
        final Repository oldRepository = new RestRepository(oldProject, REPOSITORY_KEY);
        try {
            try {
                oldRepository.delete();
            } catch (Exception e) {

            }
            oldProject.delete();
        } catch (Exception e) {

        }

        performPostQuery(stashUrl.toString() + STASH_REST_PATH + REST_PROJECTS, new BuildJSON()
                .addKey(KEY, projectKey).addKey(NAME, projectKey).addKey("description", projectKey)
                .build());
        return new RestProject(projectKey);
    }

    private void performQuery(HttpRequestBase method, String data) {
        try {
            final HttpResponse response = httpClient.execute(method, httpContext);
            if (response.getStatusLine().getStatusCode() / 100 != 2) {
                throw new RuntimeException("Error performing HTTP request: "
                        + response.getStatusLine().getReasonPhrase());
            }
        } catch (ClientProtocolException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            method.reset();
        }
    }

    private void performEntityQuery(HttpEntityEnclosingRequestBase method, String data) {
        try {
            method.setEntity(createHttpEntity(data));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        performQuery(method, data);
    }

    protected void performPostQuery(String url, String data) {
        performEntityQuery(new HttpPost(url), data);
    }

    protected void performPutQuery(String url, String data) {
        performEntityQuery(new HttpPut(url), data);
    }

    protected void performDeleteQuery(String url) {
        performQuery(new HttpDelete(url), (String) null);
    }

    private HttpEntity createHttpEntity(String data) throws UnsupportedEncodingException {
        if (data == null) {
            return null;
        }
        final StringEntity entity = new StringEntity(data);
        entity.setContentType("application/json");
        return entity;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Repository getRepository() {
        return repository;
    }

    protected String getNextFileName() {
        return RANDOM_FILE + (fileCounter++);
    }

    protected static ExecutionResult executeGitCommand(File workspaceDir, String... parameters) {
        final Runtime rt = Runtime.getRuntime();
        final String[] cmdArray = new String[parameters.length + 1];
        System.arraycopy(parameters, 0, cmdArray, 1, parameters.length);
        cmdArray[0] = GIT;
        try {
            final Process pr = rt.exec(cmdArray, new String[] {}, workspaceDir);
            final int exitCode = pr.waitFor();
            final String output = IOUtils.toString(pr.getInputStream());
            final String err = IOUtils.toString(pr.getErrorStream());
            return new ExecutionResult(exitCode, output, err);
        } catch (Exception e) {
            throw new GitException("Error executing command " + Arrays.toString(cmdArray), e);

        }
    }

    protected File commitBad() {
        return getWorkspace().commitNewFile("bad" + getNextFileName(), WRONG_CONTENTS);
    }

    protected File commitGood() {
        return getWorkspace().commitNewFile("good" + getNextFileName(), GOOD_CONTENTS);
    }

    /**
     * JSON request builder.
     */
    private static class BuildJSON {
        private final StringBuilder sb;
        private boolean hasData = false;
        private boolean finilized = false;

        public BuildJSON() {
            sb = new StringBuilder();
            sb.append("{ ");
        }

        public BuildJSON addKey(String key, String value) {
            return addRawKey(key, createString(value));
        }

        private String createString(String name) {
            return BLOCK_QUOTE + name + BLOCK_QUOTE;
        }

        private BuildJSON addRawKey(String key, String value) {
            if (finilized) {
                throw new IllegalStateException(
                        "This JSON builder is finilized. No additional data can be added");
            }
            if (hasData) {
                sb.append(',');
            }
            sb.append("\n\"").append(key).append("\": ").append(value);
            hasData = true;
            return this;

        }

        public String build() {
            finilized = true;
            sb.append(EOL).append("}");
            return sb.toString();
        }

        public BuildJSON addKey(String key, boolean value) {
            return addRawKey(key, Boolean.toString(value));
        }
    }

    /**
     * Project implementation via REST.
     */
    private class RestProject implements Project {
        private final String key;

        public RestProject(String key) {
            this.key = key;
        }

        @Override
        public Repository createRepository(String name) {
            performPostQuery(getUrl() + REST_REPOSITORIES, new BuildJSON().addKey(NAME, name)
                    .addKey("scmId", GIT).addKey("forkable", true).build());
            return new RestRepository(this, name);
        }

        @Override
        public String getKey() {
            return key;
        }

        public String getUrl() {
            return stashUrl.toString() + STASH_REST_PATH + REST_PROJECTS + REST_DELIMITER + key;
        }

        @Override
        public void delete() {
            performDeleteQuery(getUrl());
        }

        @Override
        public void removeRepository(String key) {
            new RestRepository(this, key).delete();
        }

        @Override
        public Repository forceCreateRepository(String key) {
            try {
                removeRepository(key);
            } catch (Exception e) {

            }
            return createRepository(key);
        }

    }

    /**
     * REST implementation of repository.
     */
    private class RestRepository implements Repository {

        private final Project project;
        private final String key;

        public RestRepository(Project project, String key) {
            this.key = key;
            this.project = project;
        }

        @Override
        public Workspace cloneRepository() {
            final File tempDir = Files.createTempDir();
            final ExecutionResult result = executeGitCommand(tempDir.getParentFile(), "clone",
                    getCloneUrl(), tempDir.getAbsolutePath());
            if (result.getExitCode() != 0) {
                throw new GitExecutionException(result);
            }
            return new WorkspaceImpl(tempDir);
        }

        @Override
        public String getCloneUrl() {
            return String.format("%s://%s:%s@%s:%d%s/scm/%s/%s", stashUrl.getProtocol(),
                    STASH_USER, STASH_PASSWORD, stashUrl.getHost(), stashUrl.getPort(),
                    stashUrl.getPath(), project.getKey(), key);
        }

        @Override
        public String getUrl() {
            return project.getUrl() + REST_REPOSITORIES + REST_DELIMITER + key;
        }

        private String getHookUrl(String hookKey) {
            return getUrl() + "/settings/hooks/" + hookKey;
        }

        private String getHookEnableUrl(String hookKey) {
            return getHookUrl(hookKey) + "/enabled";
        }

        private String getSettingsUrl(String hookKey) {
            return getHookUrl(hookKey) + "/settings";
        }

        @Override
        public void enableHook() {
            performPutQuery(getHookEnableUrl(HOOK_KEY), null);
        }

        @Override
        public void disableHook() {
            performDeleteQuery(getHookEnableUrl(HOOK_KEY));
        }

        @Override
        public void delete() {
            performDeleteQuery(getUrl());
        }

        @Override
        public void setHookSettings(String excludedFiles, boolean allowInherited) {
            performPutQuery(
                    getSettingsUrl(HOOK_KEY),
                    new BuildJSON().addKey(Constants.SETTING_EXCLUDED_FILES, excludedFiles)
                            .addKey(Constants.SETTING_ALLOW_INHERITED_EOL, allowInherited).build());
        }
    }

    /**
     * Implementation of workspace.
     */
    private class WorkspaceImpl implements Workspace {

        private final File workspaceDir;

        public WorkspaceImpl(File workspaceDir) {
            this.workspaceDir = workspaceDir;
        }

        private ExecutionResult executeGitCommandImpl(String... parameters) {
            return executeGitCommand(workspaceDir, parameters);
        }

        private void executeCommand(String... parameters) {
            final ExecutionResult result = executeGitCommandImpl(parameters);
            if (result.getExitCode() != 0) {
                throw new GitExecutionException(result);
            }
        }

        private void writeToFile(File file, String contents) {
            try {
                FileUtils.write(file, contents);
            } catch (IOException e) {
                throw new GitException("Error writing to file " + file, e);
            }
        }

        @Override
        public File commitNewFile(String filename, String contents) {
            final File newFile = new File(workspaceDir, filename);
            writeToFile(newFile, contents);
            add(newFile.getAbsolutePath());
            commit(filename + " added");
            return newFile;
        }

        @Override
        public void add(String filename) {
            executeCommand("add", filename);
        }

        public void commit(String message) {
            executeCommand("commit", GIT_M, message);
        }

        @Override
        public void checkout(String branchName) {
            executeCommand("checkout", branchName);
        }

        @Override
        public void branch(String branchName) {
            executeCommand(GIT_BRANCH, branchName);
        }

        private boolean isSucceeded(String... parameters) {
            final ExecutionResult result = executeGitCommandImpl(parameters);
            return result.getExitCode() == 0;
        }

        @Override
        public boolean push() {
            return isSucceeded(GIT_PUSH);
        }

        @Override
        public boolean push(String branchName) {
            return isSucceeded(GIT_PUSH, GIT_ORIGIN, branchName);
        }

        @Override
        public boolean pushForce(String targetBranch) {
            final ExecutionResult result = executeGitCommandImpl(GIT_PUSH, "--force", GIT_ORIGIN,
                    "HEAD:" + targetBranch);
            return result.getExitCode() == 0;
        }

        @Override
        public boolean pushRemoval(String branchName) {
            final ExecutionResult result = executeGitCommandImpl(GIT_PUSH, GIT_ORIGIN, DEL_PREFIX
                    + branchName);
            return result.getExitCode() == 0;
        }

        @Override
        public void setCrlf(String crlf) {
            config("core.autocrlf", crlf);
        }

        @Override
        public File getWorkDir() {
            return workspaceDir;
        }

        @Override
        public void config(String... parameters) {
            executeCommand((String[]) ArrayUtils.addAll(new String[] {"config", "--local"},
                    parameters));
        }

        @Override
        public void commitNewContents(File targetFile, String newContents) {
            writeToFile(targetFile, newContents);
            add(targetFile.getPath());
            commit("Changed contents of file " + targetFile.getPath());
        }

        @Override
        public void createTag(String tagName) {
            executeCommand(GIT_TAG, tagName);
        }

        @Override
        public void createTag(String tagName, String comment) {
            executeCommand(GIT_TAG, tagName, GIT_M, comment);
        }

    }

    /**
     * Class represents process execution result.
     */
    private static class ExecutionResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public ExecutionResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

    }

    /**
     * Exception to throw on git operations error.
     * @author Pavel Baranchikov
     */
    public static class GitExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GitExecutionException(ExecutionResult result) {
            super("Error executing git command\n" + result.getStdout() + EOL + result.getStderr());
        }
    }

    /**
     * Exception to throw on git operations error.
     * @author Pavel Baranchikov
     */
    public static class GitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GitException(String commands, Throwable cause) {
            super(commands, cause);
        }
    }

}