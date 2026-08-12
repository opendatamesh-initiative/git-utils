package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import org.opendatamesh.platform.git.model.CreatePullRequest;
import org.opendatamesh.platform.git.model.PullRequest;
import org.springframework.util.StringUtils;

public abstract class BitbucketCreatePullRequestMapper {

    private BitbucketCreatePullRequestMapper() {
    }

    public static BitbucketCreatePullRequestReq fromInternalModel(CreatePullRequest createPullRequest, String resolvedTargetBranch) {
        if (createPullRequest == null) {
            return null;
        }
        BitbucketCreatePullRequestReq req = new BitbucketCreatePullRequestReq();
        req.setTitle(createPullRequest.getTitle());
        if (StringUtils.hasText(createPullRequest.getBody())) {
            req.setDescription(createPullRequest.getBody());
        }

        BitbucketCreatePullRequestEndpointReq source = new BitbucketCreatePullRequestEndpointReq();
        source.setBranch(new BitbucketCreatePullRequestBranchReq(createPullRequest.getSourceBranch()));
        req.setSource(source);

        BitbucketCreatePullRequestEndpointReq destination = new BitbucketCreatePullRequestEndpointReq();
        destination.setBranch(new BitbucketCreatePullRequestBranchReq(resolvedTargetBranch));
        req.setDestination(destination);
        return req;
    }

    public static PullRequest toInternalModel(BitbucketCreatePullRequestRes res, CreatePullRequest request, String resolvedTargetBranch) {
        if (res == null) {
            return null;
        }
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(res.getId() != null ? String.valueOf(res.getId()) : null);
        if (res.getLinks() != null && res.getLinks().getHtml() != null) {
            pullRequest.setWebUrl(res.getLinks().getHtml().getHref());
        }
        pullRequest.setState(res.getState());
        pullRequest.setTitle(res.getTitle() != null ? res.getTitle() : request.getTitle());
        pullRequest.setBody(res.getDescription() != null ? res.getDescription() : request.getBody());
        pullRequest.setSourceBranch(branchName(res.getSource(), request.getSourceBranch()));
        pullRequest.setTargetBranch(branchName(res.getDestination(), resolvedTargetBranch));
        return pullRequest;
    }

    private static String branchName(BitbucketCreatePullRequestEndpointRes endpoint, String fallback) {
        if (endpoint != null && endpoint.getBranch() != null && StringUtils.hasText(endpoint.getBranch().getName())) {
            return endpoint.getBranch().getName();
        }
        return fallback;
    }
}
