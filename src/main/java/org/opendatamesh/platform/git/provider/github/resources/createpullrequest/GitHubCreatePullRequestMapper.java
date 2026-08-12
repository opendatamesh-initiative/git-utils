package org.opendatamesh.platform.git.provider.github.resources.createpullrequest;

import org.opendatamesh.platform.git.model.CreatePullRequest;
import org.opendatamesh.platform.git.model.PullRequest;
import org.springframework.util.StringUtils;

public abstract class GitHubCreatePullRequestMapper {

    private GitHubCreatePullRequestMapper() {
    }

    public static GitHubCreatePullRequestReq fromInternalModel(CreatePullRequest createPullRequest, String resolvedTargetBranch) {
        if (createPullRequest == null) {
            return null;
        }
        GitHubCreatePullRequestReq req = new GitHubCreatePullRequestReq();
        req.setHead(createPullRequest.getSourceBranch());
        req.setBase(resolvedTargetBranch);
        req.setTitle(createPullRequest.getTitle());
        if (StringUtils.hasText(createPullRequest.getBody())) {
            req.setBody(createPullRequest.getBody());
        }
        return req;
    }

    public static PullRequest toInternalModel(GitHubCreatePullRequestRes res, CreatePullRequest request, String resolvedTargetBranch) {
        if (res == null) {
            return null;
        }
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(res.getNumber() != null ? String.valueOf(res.getNumber()) : null);
        pullRequest.setWebUrl(res.getHtmlUrl());
        pullRequest.setState(res.getState());
        pullRequest.setTitle(res.getTitle() != null ? res.getTitle() : request.getTitle());
        pullRequest.setBody(res.getBody() != null ? res.getBody() : request.getBody());
        pullRequest.setSourceBranch(res.getHead() != null && StringUtils.hasText(res.getHead().getRef())
                ? res.getHead().getRef()
                : request.getSourceBranch());
        pullRequest.setTargetBranch(res.getBase() != null && StringUtils.hasText(res.getBase().getRef())
                ? res.getBase().getRef()
                : resolvedTargetBranch);
        return pullRequest;
    }
}
