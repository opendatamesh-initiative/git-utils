package org.opendatamesh.platform.git.provider.github.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubCreatePullRequestRes {
    @JsonProperty("number")
    private Long number;
    @JsonProperty("html_url")
    private String htmlUrl;
    @JsonProperty("state")
    private String state;
    @JsonProperty("title")
    private String title;
    @JsonProperty("body")
    private String body;
    @JsonProperty("head")
    private GitHubCreatePullRequestBranchRes head;
    @JsonProperty("base")
    private GitHubCreatePullRequestBranchRes base;

    public Long getNumber() {
        return number;
    }

    public void setNumber(Long number) {
        this.number = number;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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

    public GitHubCreatePullRequestBranchRes getHead() {
        return head;
    }

    public void setHead(GitHubCreatePullRequestBranchRes head) {
        this.head = head;
    }

    public GitHubCreatePullRequestBranchRes getBase() {
        return base;
    }

    public void setBase(GitHubCreatePullRequestBranchRes base) {
        this.base = base;
    }
}
