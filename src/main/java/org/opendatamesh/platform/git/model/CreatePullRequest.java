package org.opendatamesh.platform.git.model;

/**
 * Explicit create-input for same-repository Pull Request / Merge Request creation.
 * Does not include result-only fields such as id, webUrl, or state.
 */
public class CreatePullRequest {
    private String sourceBranch;
    private String targetBranch;
    private String title;
    private String body;

    public CreatePullRequest() {
    }

    public CreatePullRequest(String sourceBranch, String targetBranch, String title, String body) {
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.title = title;
        this.body = body;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public void setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
