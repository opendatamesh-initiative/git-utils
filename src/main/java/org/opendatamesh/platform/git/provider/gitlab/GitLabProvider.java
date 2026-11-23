package org.opendatamesh.platform.git.provider.gitlab;

import org.opendatamesh.platform.git.exceptions.GitProviderConfigurationException;
import org.opendatamesh.platform.git.exceptions.GitClientException;
import org.opendatamesh.platform.git.exceptions.GitProviderAuthenticationException;
import org.opendatamesh.platform.git.git.GitOperation;
import org.opendatamesh.platform.git.git.GitOperationImpl;
import org.opendatamesh.platform.git.model.*;
import org.opendatamesh.platform.git.provider.GitProvider;
import org.opendatamesh.platform.git.provider.GitProviderCredential;
import org.opendatamesh.platform.git.provider.gitlab.comparator.GitLabCommitComparator;
import org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest.GitLabCreateMergeRequestMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest.GitLabCreateMergeRequestReq;
import org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest.GitLabCreateMergeRequestRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.createrepository.GitLabCreateRepositoryMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.createrepository.GitLabCreateRepositoryProjectRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.createrepository.GitLabCreateRepositoryReq;
import org.opendatamesh.platform.git.provider.gitlab.resources.getcurrentuser.GitLabGetCurrentUserMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.getcurrentuser.GitLabGetCurrentUserUserRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.getorganization.GitLabGetOrganizationGroupRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.getorganization.GitLabGetOrganizationMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.getrepository.GitLabGetRepositoryMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.getrepository.GitLabGetRepositoryProjectRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listbranches.GitLabListBranchesBranchRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listbranches.GitLabListBranchesMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listcommits.GitLabCompareCommitsRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listcommits.GitLabListCommitsCommitRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listcommits.GitLabListCommitsMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listmembers.GitLabListMembersMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listmembers.GitLabListMembersUserRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations.GitLabListOrganizationsGraphQlReq;
import org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations.GitLabListOrganizationsGraphQlRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations.GitLabListOrganizationsGroupRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations.GitLabListOrganizationsMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listrepositories.GitLabListRepositoriesMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listrepositories.GitLabListRepositoriesProjectRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listtags.GitLabListTagsMapper;
import org.opendatamesh.platform.git.provider.gitlab.resources.listtags.GitLabListTagsTagRes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * GitLab provider implementation
 * <p>
 * Supported authentication methods:
 * - OAuth 2.0 (Bearer token) - ✅ Recommended
 * - PAT as Bearer token - ✅ Recommended
 */
public class GitLabProvider implements GitProvider {

    /**
     * GitLab "Developer" access level: the lowest role a group can allow to create projects.
     */
    static final int GITLAB_DEVELOPER_ACCESS_LEVEL = 30;

    /**
     * Page size used when reading all the groups from GitLab (100 is GitLab's maximum).
     */
    static final int ORGANIZATIONS_FETCH_PAGE_SIZE = 100;

    /**
     * Upper bound on the pages read from each source when listing organizations (so at most 2000 groups each).
     */
    static final int ORGANIZATIONS_MAX_FETCHED_PAGES = 20;

    private static final int LOGGED_RESPONSE_MAX_LENGTH = 500;

    private static final Logger logger = LoggerFactory.getLogger(GitLabProvider.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final GitProviderCredential credential;

    public GitLabProvider(String baseUrl, RestTemplate restTemplate, GitProviderCredential credential) throws GitProviderConfigurationException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required and cannot be null or empty");
        }
        this.baseUrl = baseUrl.trim();
        this.restTemplate = restTemplate;
        this.credential = credential;
    }

    @Override
    public User getCurrentUser() {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<GitLabGetCurrentUserUserRes> response = restTemplate.exchange(
                    baseUrl + "/api/v4/user",
                    HttpMethod.GET,
                    entity,
                    GitLabGetCurrentUserUserRes.class
            );

            GitLabGetCurrentUserUserRes userResponse = response.getBody();
            if (userResponse != null) {
                return GitLabGetCurrentUserMapper.toInternalModel(userResponse);
            }

            throw new GitClientException(404, "Failed to get current user: response body is null");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to get current user: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to get current user: " + e.getMessage());
        }
    }

    @Override
    public Page<Organization> listOrganizations(Pageable page) {
        // Two sources, merged by group ID:
        // 1. REST: the groups the user is a direct member of with at least the Developer role, plus their
        //    subgroups. This is the list we always returned, kept so that pickers of existing repositories don't
        //    lose groups where the user is Developer but only Maintainers can create projects.
        // 2. GraphQL CREATE_PROJECTS: the groups where the user can create projects, including the groups
        //    reached through group sharing ("invited groups") and their subgroups, which the REST filter
        //    min_access_level leaves out (BDMD-5775). Optional: on older GitLab or when it fails we fall back
        //    to the REST list alone.
        // Both sources are read in full and the merged list is paged here, because the two can't be paged together.
        Map<String, SortableOrganization> organizationsById = new LinkedHashMap<>();
        for (GitLabListOrganizationsGroupRes groupResponse : fetchMemberGroups()) {
            Organization org = GitLabListOrganizationsMapper.toInternalModel(groupResponse);
            if (org != null) {
                organizationsById.putIfAbsent(org.getId(), new SortableOrganization(groupResponse.getFullPath(), org));
            }
        }
        for (GitLabListOrganizationsGraphQlRes.Group group : fetchGroupsAcceptingProjectCreation()) {
            Organization org = GitLabListOrganizationsMapper.toInternalModel(group);
            if (org != null) {
                organizationsById.putIfAbsent(org.getId(), new SortableOrganization(group.getFullPath(), org));
            }
        }

        // Sorted by the name callers display, with the full path breaking ties between same-named groups.
        List<Organization> organizations = organizationsById.values().stream()
                .sorted(Comparator.comparing(SortableOrganization::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SortableOrganization::fullPath, String.CASE_INSENSITIVE_ORDER))
                .map(SortableOrganization::organization)
                .toList();

        if (page.isUnpaged()) {
            return new PageImpl<>(organizations, page, organizations.size());
        }
        int from = (int) Math.min(page.getOffset(), organizations.size());
        int to = Math.min(from + page.getPageSize(), organizations.size());
        return new PageImpl<>(organizations.subList(from, to), page, organizations.size());
    }

    private record SortableOrganization(String fullPath, Organization organization) {
        SortableOrganization {
            fullPath = fullPath != null ? fullPath : "";
        }

        String name() {
            return organization.getName() != null ? organization.getName() : "";
        }
    }

    /**
     * Reads all pages of {@code GET /groups?min_access_level=30}, up to {@link #ORGANIZATIONS_MAX_FETCHED_PAGES} pages.
     */
    private List<GitLabListOrganizationsGroupRes> fetchMemberGroups() {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Filtering on owned=true would hide the groups where the user is Maintainer or Developer.
            String uriTemplate = baseUrl + "/api/v4/groups?page={page}&per_page={perPage}&min_access_level={minAccessLevel}";
            List<GitLabListOrganizationsGroupRes> groups = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= ORGANIZATIONS_MAX_FETCHED_PAGES; pageNumber++) {
                Map<String, Object> uriVariables = new HashMap<>();
                uriVariables.put("page", pageNumber);
                uriVariables.put("perPage", ORGANIZATIONS_FETCH_PAGE_SIZE);
                uriVariables.put("minAccessLevel", GITLAB_DEVELOPER_ACCESS_LEVEL);

                ResponseEntity<GitLabListOrganizationsGroupRes[]> response = restTemplate.exchange(
                        uriTemplate,
                        HttpMethod.GET,
                        entity,
                        GitLabListOrganizationsGroupRes[].class,
                        uriVariables
                );

                GitLabListOrganizationsGroupRes[] groupResponses = response != null ? response.getBody() : null;
                if (groupResponses == null || groupResponses.length == 0) {
                    break;
                }
                groups.addAll(Arrays.asList(groupResponses));
                if (groupResponses.length < ORGANIZATIONS_FETCH_PAGE_SIZE) {
                    break;
                }
                if (pageNumber == ORGANIZATIONS_MAX_FETCHED_PAGES) {
                    logger.warn("GitLab member groups: stopped after {} pages ({} groups); further groups, if any, are not listed",
                            ORGANIZATIONS_MAX_FETCHED_PAGES, groups.size());
                }
            }
            return groups;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list organizations: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list organizations: " + e.getMessage());
        }
    }

    /**
     * Reads all pages of the GraphQL {@code currentUser.groups(permissionScope: CREATE_PROJECTS)} query, up to
     * {@link #ORGANIZATIONS_MAX_FETCHED_PAGES} pages. Returns what was read so far (possibly nothing) when the query
     * fails, e.g. on GitLab older than 15.11, with GraphQL disabled or with a token lacking the read_api scope.
     */
    private List<GitLabListOrganizationsGraphQlRes.Group> fetchGroupsAcceptingProjectCreation() {
        List<GitLabListOrganizationsGraphQlRes.Group> groups = new ArrayList<>();
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String after = null;
            for (int pageNumber = 1; pageNumber <= ORGANIZATIONS_MAX_FETCHED_PAGES; pageNumber++) {
                HttpEntity<GitLabListOrganizationsGraphQlReq> entity = new HttpEntity<>(
                        new GitLabListOrganizationsGraphQlReq(ORGANIZATIONS_FETCH_PAGE_SIZE, after), headers);

                ResponseEntity<GitLabListOrganizationsGraphQlRes> response = restTemplate.exchange(
                        baseUrl + "/api/graphql",
                        HttpMethod.POST,
                        entity,
                        GitLabListOrganizationsGraphQlRes.class
                );

                GitLabListOrganizationsGraphQlRes body = response != null ? response.getBody() : null;
                if (body == null) {
                    logger.warn("GitLab GraphQL query for groups accepting project creation returned no body");
                    break;
                }
                if (body.getErrors() != null && !body.getErrors().isEmpty()) {
                    logger.warn("GitLab GraphQL query for groups accepting project creation failed: {}",
                            body.getErrors().stream().map(GitLabListOrganizationsGraphQlRes.Error::getMessage).toList());
                    break;
                }
                GitLabListOrganizationsGraphQlRes.GroupConnection connection = body.getData() != null
                        && body.getData().getCurrentUser() != null
                        ? body.getData().getCurrentUser().getGroups()
                        : null;
                if (connection == null) {
                    break;
                }
                if (connection.getNodes() != null) {
                    groups.addAll(connection.getNodes());
                }
                GitLabListOrganizationsGraphQlRes.PageInfo pageInfo = connection.getPageInfo();
                if (pageInfo == null || !pageInfo.isHasNextPage() || !StringUtils.hasText(pageInfo.getEndCursor())) {
                    break;
                }
                if (pageNumber == ORGANIZATIONS_MAX_FETCHED_PAGES) {
                    logger.warn("GitLab groups accepting project creation: stopped after {} pages ({} groups); further groups are not listed",
                            ORGANIZATIONS_MAX_FETCHED_PAGES, groups.size());
                    break;
                }
                after = pageInfo.getEndCursor();
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            logger.warn("GitLab GraphQL query for groups accepting project creation failed with status {}: {}",
                    e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            logger.warn("GitLab GraphQL query for groups accepting project creation failed: {}", e.getMessage());
        }
        return groups;
    }

    /**
     * Shortens a response body for logging: when GraphQL is disabled or behind a proxy it can be a whole HTML page.
     */
    private static String abbreviate(String text) {
        if (text == null || text.length() <= LOGGED_RESPONSE_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, LOGGED_RESPONSE_MAX_LENGTH) + "... (truncated)";
    }

    @Override
    public Optional<Organization> getOrganization(String id) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String uriTemplate = baseUrl + "/api/v4/groups/{id}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("id", id);

            ResponseEntity<GitLabGetOrganizationGroupRes> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabGetOrganizationGroupRes.class,
                    uriVariables
            );

            GitLabGetOrganizationGroupRes groupResponse = response.getBody();
            if (groupResponse != null) {
                Organization org = GitLabGetOrganizationMapper.toInternalModel(groupResponse);
                if (org != null) {
                    return Optional.of(org);
                }
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to get organization: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to get organization: " + e.getMessage());
        }

        return Optional.empty();
    }

    @Override
    public Page<User> listMembers(Organization org, Pageable page) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String uriTemplate = baseUrl + "/api/v4/groups/{groupId}/members?page={page}&per_page={perPage}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("groupId", org.getId());
            uriVariables.put("page", page.getPageNumber() + 1);
            uriVariables.put("perPage", page.getPageSize());

            ResponseEntity<GitLabListMembersUserRes[]> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabListMembersUserRes[].class,
                    uriVariables
            );

            List<User> members = new ArrayList<>();
            GitLabListMembersUserRes[] userResponses = response.getBody();
            if (userResponses != null) {
                for (GitLabListMembersUserRes userResponse : userResponses) {
                    User user = GitLabListMembersMapper.toInternalModel(userResponse);
                    if (user != null) {
                        members.add(user);
                    }
                }
            }

            return new PageImpl<>(members, page, members.size());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list organization members: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list organization members: " + e.getMessage());
        }
    }

    @Override
    public Page<Repository> listRepositories(Organization org, User usr, MultiValueMap<String, String> parameters, Pageable page) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String uriTemplate;
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("page", page.getPageNumber() + 1);
            uriVariables.put("perPage", page.getPageSize());

            if (org != null) {
                uriTemplate = baseUrl + "/api/v4/groups/{groupId}/projects?page={page}&per_page={perPage}";
                uriVariables.put("groupId", org.getId());
            } else {
                uriTemplate = baseUrl + "/api/v4/users/{userId}/projects?page={page}&per_page={perPage}";
                uriVariables.put("userId", usr.getId());
            }

            ResponseEntity<GitLabListRepositoriesProjectRes[]> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabListRepositoriesProjectRes[].class,
                    uriVariables
            );

            List<Repository> repositories = new ArrayList<>();
            GitLabListRepositoriesProjectRes[] projectResponses = response.getBody();
            if (projectResponses != null) {
                RepositoryOwnerType ownerType = org != null ? RepositoryOwnerType.ORGANIZATION
                        : RepositoryOwnerType.ACCOUNT;
                for (GitLabListRepositoriesProjectRes projectResponse : projectResponses) {
                    Repository repo = GitLabListRepositoriesMapper.toInternalModel(projectResponse, ownerType);
                    if (repo != null) {
                        repositories.add(repo);
                    }
                }
            }

            return new PageImpl<>(repositories, page, repositories.size());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list repositories: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list repositories: " + e.getMessage());
        }
    }

    @Override
    public Optional<Repository> getRepository(String id, String ownerId) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String uriTemplate = baseUrl + "/api/v4/projects/{id}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("id", id);

            ResponseEntity<GitLabGetRepositoryProjectRes> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabGetRepositoryProjectRes.class,
                    uriVariables
            );

            GitLabGetRepositoryProjectRes projectResponse = response.getBody();
            if (projectResponse != null) {
                Repository repo = GitLabGetRepositoryMapper.toInternalModel(projectResponse);
                if (repo != null) {
                    return Optional.of(repo);
                }
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to get repository: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to get repository: " + e.getMessage());
        }

        return Optional.empty();
    }

    private static String describeNamespace(Repository repository) {
        if (repository != null && repository.getOwnerType() == RepositoryOwnerType.ORGANIZATION) {
            return "group " + repository.getOwnerId();
        }
        return "the user's personal namespace";
    }

    @Override
    public Repository createRepository(Repository repositoryToCreate) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            headers.set("Content-Type", "application/json");

            // Create request payload
            GitLabCreateRepositoryReq request = GitLabCreateRepositoryMapper.fromInternalModel(repositoryToCreate);

            HttpEntity<GitLabCreateRepositoryReq> entity = new HttpEntity<>(request, headers);

            ResponseEntity<GitLabCreateRepositoryProjectRes> response = restTemplate.exchange(
                    baseUrl + "/api/v4/projects",
                    HttpMethod.POST,
                    entity,
                    GitLabCreateRepositoryProjectRes.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                GitLabCreateRepositoryProjectRes projectResponse = response.getBody();
                return GitLabCreateRepositoryMapper.toInternalModel(projectResponse, repositoryToCreate.getOwnerType());
            }

            throw new GitClientException(response.getStatusCode().value(),
                    "Failed to create repository. Status: " + response.getStatusCode());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            if (e.getStatusCode().value() == 403) {
                throw new GitClientException(403,
                        "GitLab denied the repository creation: the user's role is not allowed to create projects in "
                                + describeNamespace(repositoryToCreate)
                                + ". Check the group setting \"Roles allowed to create projects\" or use an account with a higher role. "
                                + "GitLab response: " + e.getResponseBodyAsString());
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to create repository: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to create repository: " + e.getMessage());
        }
    }

    @Override
    public Page<Commit> listCommits(Repository repository, CommitListFilter filter, Pageable page) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String projectId = repository.getId();

            if (filter instanceof CommitListRangeFilter r) {
                String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/repository/compare?from={from}&to={to}";
                Map<String, Object> uriVariables = constructUriVariablesListCommitsCompare(projectId, r.from().getRefValue(), r.to().getRefValue());
                ResponseEntity<GitLabCompareCommitsRes> response = callApiListCommitsCompare(uriTemplate, entity, uriVariables);
                List<Commit> commits = mappingListCommitsCompareToInternalModel(response);
                commits.sort(new GitLabCommitComparator());
                return new PageImpl<>(commits, page, commits.size());
            }
            if (filter instanceof CommitListSingleBranchFilter s) {
                String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/repository/commits?ref_name={branchName}&page={page}&per_page={perPage}";
                Map<String, Object> uriVariables = constructUriVariablesListCommits(projectId, page);
                uriVariables.put("branchName", s.branch().getRefValue());
                ResponseEntity<GitLabListCommitsCommitRes[]> response = callApiListCommits(uriTemplate, entity, uriVariables);
                List<Commit> commits = mappingListCommitsToInternalModel(response);
                return new PageImpl<>(commits, page, commits.size());
            }
            // NoCommitListFilter: list default branch
            String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/repository/commits?page={page}&per_page={perPage}";
            Map<String, Object> uriVariables = constructUriVariablesListCommits(projectId, page);
            ResponseEntity<GitLabListCommitsCommitRes[]> response = callApiListCommits(uriTemplate, entity, uriVariables);
            List<Commit> commits = mappingListCommitsToInternalModel(response);
            return new PageImpl<>(commits, page, commits.size());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list commits: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list commits: " + e.getMessage());
        }
    }

    @Override
    public Page<Branch> listBranches(Repository repository, Pageable page) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Extract project ID from repository
            String projectId = repository.getId();

            String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/repository/branches?page={page}&per_page={perPage}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("projectId", projectId);
            uriVariables.put("page", page.getPageNumber() + 1);
            uriVariables.put("perPage", page.getPageSize());

            ResponseEntity<GitLabListBranchesBranchRes[]> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabListBranchesBranchRes[].class,
                    uriVariables
            );

            List<Branch> branches = new ArrayList<>();
            GitLabListBranchesBranchRes[] branchResponses = response.getBody();
            if (branchResponses != null) {
                for (GitLabListBranchesBranchRes branchResponse : branchResponses) {
                    Branch branch = GitLabListBranchesMapper.toInternalModel(branchResponse);
                    if (branch != null) {
                        branches.add(branch);
                    }
                }
            }

            return new PageImpl<>(branches, page, branches.size());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list branches: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list branches: " + e.getMessage());
        }
    }

    @Override
    public Page<Tag> listTags(Repository repository, Pageable page) {
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Extract project ID from repository
            String projectId = repository.getId();

            String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/repository/tags?page={page}&per_page={perPage}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("projectId", projectId);
            uriVariables.put("page", page.getPageNumber() + 1);
            uriVariables.put("perPage", page.getPageSize());

            ResponseEntity<GitLabListTagsTagRes[]> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.GET,
                    entity,
                    GitLabListTagsTagRes[].class,
                    uriVariables
            );

            List<Tag> tags = new ArrayList<>();
            GitLabListTagsTagRes[] tagResponses = response.getBody();
            if (tagResponses != null) {
                for (GitLabListTagsTagRes tagResponse : tagResponses) {
                    Tag tag = GitLabListTagsMapper.toInternalModel(tagResponse);
                    if (tag != null) {
                        tags.add(tag);
                    }
                }
            }

            return new PageImpl<>(tags, page, tags.size());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to list tags: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to list tags: " + e.getMessage());
        }
    }

    @Override
    public GitOperation gitOperation() {
        return new GitOperationImpl(credential.createGitCredential());
    }

    @Override
    public PullRequest createPullRequest(Repository repository, CreatePullRequest createPullRequest) {
        String resolvedTarget = resolveAndValidateCreatePullRequest(repository, createPullRequest);
        try {
            HttpHeaders headers = credential.createGitProviderHeaders();
            headers.set("Content-Type", "application/json");

            GitLabCreateMergeRequestReq request = GitLabCreateMergeRequestMapper.fromInternalModel(createPullRequest, resolvedTarget);
            HttpEntity<GitLabCreateMergeRequestReq> entity = new HttpEntity<>(request, headers);

            String uriTemplate = baseUrl + "/api/v4/projects/{projectId}/merge_requests";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("projectId", repository.getId());

            ResponseEntity<GitLabCreateMergeRequestRes> response = restTemplate.exchange(
                    uriTemplate,
                    HttpMethod.POST,
                    entity,
                    GitLabCreateMergeRequestRes.class,
                    uriVariables
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return GitLabCreateMergeRequestMapper.toInternalModel(response.getBody(), createPullRequest, resolvedTarget);
            }
            throw new GitClientException(response.getStatusCode().value(),
                    "Failed to create merge request. Status: " + response.getStatusCode());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new GitProviderAuthenticationException("GitLab authentication failed with provider. Please check your credentials.");
            }
            throw new GitClientException(e.getStatusCode().value(),
                    "GitLab request failed to create merge request: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new GitClientException(500, "GitLab request failed to create merge request: " + e.getMessage());
        }
    }

    private String resolveAndValidateCreatePullRequest(Repository repository, CreatePullRequest createPullRequest) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository is required");
        }
        if (createPullRequest == null) {
            throw new IllegalArgumentException("CreatePullRequest is required");
        }
        if (!StringUtils.hasText(createPullRequest.getSourceBranch())) {
            throw new IllegalArgumentException("sourceBranch is required");
        }
        if (!StringUtils.hasText(createPullRequest.getTitle())) {
            throw new IllegalArgumentException("title is required");
        }
        String resolvedTarget = StringUtils.hasText(createPullRequest.getTargetBranch())
                ? createPullRequest.getTargetBranch()
                : repository.getDefaultBranch();
        if (!StringUtils.hasText(resolvedTarget)) {
            throw new IllegalArgumentException("targetBranch is required when repository defaultBranch is blank");
        }
        if (createPullRequest.getSourceBranch().equals(resolvedTarget)) {
            throw new IllegalArgumentException("sourceBranch and targetBranch must be different");
        }
        return resolvedTarget;
    }

    private Map<String, Object> constructUriVariablesListCommitsCompare(String projectId, String from, String to) {
        Map<String, Object> uriVariables = new HashMap<>();
        uriVariables.put("projectId", projectId);
        uriVariables.put("from", from);
        uriVariables.put("to", to);
        return uriVariables;
    }

    private Map<String, Object> constructUriVariablesListCommits(String projectId, Pageable page) {
        Map<String, Object> uriVariables = new HashMap<>();
        uriVariables.put("projectId", projectId);
        uriVariables.put("page", page.getPageNumber() + 1);
        uriVariables.put("perPage", page.getPageSize());
        return uriVariables;
    }

    private ResponseEntity<GitLabCompareCommitsRes> callApiListCommitsCompare(String uriTemplate, HttpEntity<String> entity, Map<String, Object> uriVariables) {
        return restTemplate.exchange(
                uriTemplate,
                HttpMethod.GET,
                entity,
                GitLabCompareCommitsRes.class,
                uriVariables
        );
    }

    private ResponseEntity<GitLabListCommitsCommitRes[]> callApiListCommits(String uriTemplate, HttpEntity<String> entity, Map<String, Object> uriVariables) {
        return restTemplate.exchange(
                uriTemplate,
                HttpMethod.GET,
                entity,
                GitLabListCommitsCommitRes[].class,
                uriVariables
        );
    }

    private List<Commit> mappingListCommitsCompareToInternalModel(ResponseEntity<GitLabCompareCommitsRes> response) {
        List<Commit> commits = new ArrayList<>();
        GitLabCompareCommitsRes compareResponses = response.getBody();
        if (compareResponses != null && compareResponses.getCommits() != null) {
            for (GitLabCompareCommitsRes.CompareCommitRes compareResponse : compareResponses.getCommits()) {
                Commit commit = GitLabListCommitsMapper.toInternalModel(compareResponse);
                if (commit != null) {
                    commits.add(commit);
                }
            }
        }
        return commits;
    }

    private List<Commit> mappingListCommitsToInternalModel(ResponseEntity<GitLabListCommitsCommitRes[]> response) {
        List<Commit> commits = new ArrayList<>();
        GitLabListCommitsCommitRes[] commitResponses = response.getBody();
        if (commitResponses != null) {
            for (GitLabListCommitsCommitRes commitResponse : commitResponses) {
                Commit commit = GitLabListCommitsMapper.toInternalModel(commitResponse);
                if (commit != null) {
                    commits.add(commit);
                }
            }
        }
        return commits;
    }
}
