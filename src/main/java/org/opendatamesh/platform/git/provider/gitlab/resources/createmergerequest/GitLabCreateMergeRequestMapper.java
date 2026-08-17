package org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest;

import org.opendatamesh.platform.git.model.CreatePullRequest;
import org.opendatamesh.platform.git.model.PullRequest;
import org.springframework.util.StringUtils;

public abstract class GitLabCreateMergeRequestMapper {

    private GitLabCreateMergeRequestMapper() {
    }

    public static GitLabCreateMergeRequestReq fromInternalModel(CreatePullRequest createPullRequest, String resolvedTargetBranch) {
        if (createPullRequest == null) {
            return null;
        }
        GitLabCreateMergeRequestReq req = new GitLabCreateMergeRequestReq();
        req.setSourceBranch(createPullRequest.getSourceBranch());
        req.setTargetBranch(resolvedTargetBranch);
        req.setTitle(createPullRequest.getTitle());
        if (StringUtils.hasText(createPullRequest.getBody())) {
            req.setDescription(createPullRequest.getBody());
        }
        return req;
    }

    public static PullRequest toInternalModel(GitLabCreateMergeRequestRes res, CreatePullRequest request, String resolvedTargetBranch) {
        if (res == null) {
            return null;
        }
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(res.getIid() != null ? String.valueOf(res.getIid()) : null);
        pullRequest.setWebUrl(res.getWebUrl());
        pullRequest.setState(res.getState());
        pullRequest.setTitle(res.getTitle() != null ? res.getTitle() : request.getTitle());
        pullRequest.setBody(res.getDescription() != null ? res.getDescription() : request.getBody());
        pullRequest.setSourceBranch(StringUtils.hasText(res.getSourceBranch()) ? res.getSourceBranch() : request.getSourceBranch());
        pullRequest.setTargetBranch(StringUtils.hasText(res.getTargetBranch()) ? res.getTargetBranch() : resolvedTargetBranch);
        return pullRequest;
    }
}
