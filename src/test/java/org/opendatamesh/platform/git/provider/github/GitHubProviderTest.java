package org.opendatamesh.platform.git.provider.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendatamesh.platform.git.exceptions.GitClientException;
import org.opendatamesh.platform.git.exceptions.GitProviderAuthenticationException;
import org.opendatamesh.platform.git.model.*;
import org.opendatamesh.platform.git.provider.GitProviderCredential;
import org.opendatamesh.platform.git.provider.github.credentials.GitHubPatCredential;
import org.opendatamesh.platform.git.provider.github.resources.createpullrequest.GitHubCreatePullRequestReq;
import org.opendatamesh.platform.git.provider.github.resources.createpullrequest.GitHubCreatePullRequestRes;
import org.opendatamesh.platform.git.provider.github.resources.getcurrentuser.GitHubGetCurrentUserUserRes;
import org.opendatamesh.platform.git.provider.github.resources.getorganization.GitHubGetOrganizationOrganizationRes;
import org.opendatamesh.platform.git.provider.github.resources.getrepository.GitHubGetRepositoryRepositoryRes;
import org.opendatamesh.platform.git.provider.github.resources.listbranches.GitHubListBranchesBranchRes;
import org.opendatamesh.platform.git.provider.github.resources.listcommits.GitHubCompareCommitsRes;
import org.opendatamesh.platform.git.provider.github.resources.listcommits.GitHubListCommitsCommitRes;
import org.opendatamesh.platform.git.provider.github.resources.listmembers.GitHubListMembersUserRes;
import org.opendatamesh.platform.git.provider.github.resources.listorganizations.GitHubListOrganizationsOrganizationRes;
import org.opendatamesh.platform.git.provider.github.resources.listrepositories.GitHubListRepositoriesRepositoryRes;
import org.opendatamesh.platform.git.provider.github.resources.listtags.GitHubListTagsTagRes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private GitHubProvider gitHubProvider;
    private GitProviderCredential credential;
    private String baseUrl = "https://api.github.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        credential = new GitHubPatCredential("test-token");
        gitHubProvider = new GitHubProvider(baseUrl, restTemplate, credential);
    }

    @Test
    void whenBaseUrlIsNullThenThrowException() {
        assertThatThrownBy(() -> new GitHubProvider(null, restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenBaseUrlIsBlankThenThrowException() {
        assertThatThrownBy(() -> new GitHubProvider("  ", restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenGetCurrentUserCalledThenAssertUserReturned() throws Exception {
        // Load JSON response
        GitHubGetCurrentUserUserRes userRes = loadJson("github/get_current_user.json", GitHubGetCurrentUserUserRes.class);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetCurrentUserUserRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));

        // Test
        User user = gitHubProvider.getCurrentUser();

        // Verify
        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetCurrentUserUserRes.class)
        );
    }

    @Test
    void whenListOrganizationsCalledThenAssertOrganizationsReturned() throws Exception {
        // Load JSON response
        GitHubListOrganizationsOrganizationRes[] orgsRes = loadJson("github/list_organizations.json", GitHubListOrganizationsOrganizationRes[].class);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/user/orgs?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListOrganizationsOrganizationRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgsRes, HttpStatus.OK));

        // Test
        Page<Organization> organizations = gitHubProvider.listOrganizations(pageable);

        // Verify
        assertThat(organizations).isNotNull();
        assertThat(organizations.getContent()).isNotEmpty();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/user/orgs?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListOrganizationsOrganizationRes[].class),
                anyMap()
        );
    }

    @Test
    void whenGetOrganizationCalledThenAssertOrganizationReturned() throws Exception {
        // Load JSON response
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        String orgId = "test-org";
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));

        // Test
        Optional<Organization> organization = gitHubProvider.getOrganization(orgId);

        // Verify
        assertThat(organization).isPresent();
        assertThat(organization.get().getName()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        );
    }

    @Test
    void whenListMembersCalledThenAssertMembersReturned() throws Exception {
        // Load JSON response
        GitHubListMembersUserRes[] membersRes = loadJson("github/list_members.json", GitHubListMembersUserRes[].class);
        Organization org = new Organization("test-org", "test-org", "https://github.com/test-org");
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{orgName}/members?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListMembersUserRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(membersRes, HttpStatus.OK));

        // Test
        Page<User> members = gitHubProvider.listMembers(org, pageable);

        // Verify
        assertThat(members).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/orgs/{orgName}/members?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListMembersUserRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListRepositoriesCalledThenAssertRepositoriesReturned() throws Exception {
        // Load JSON response
        GitHubListRepositoriesRepositoryRes[] reposRes = loadJson("github/list_repositories.json", GitHubListRepositoriesRepositoryRes[].class);
        Pageable pageable = PageRequest.of(0, 20);
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/user/repos?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListRepositoriesRepositoryRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(reposRes, HttpStatus.OK));

        // Test
        Page<Repository> repositories = gitHubProvider.listRepositories(null, null, parameters, pageable);

        // Verify
        assertThat(repositories).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/user/repos?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListRepositoriesRepositoryRes[].class),
                anyMap()
        );
    }

    @Test
    void whenGetRepositoryCalledThenAssertRepositoryReturned() throws Exception {
        // Load JSON response
        GitHubGetRepositoryRepositoryRes repoRes = loadJson("github/get_repository.json", GitHubGetRepositoryRepositoryRes.class);
        String repoId = "342219496";
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/repositories/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetRepositoryRepositoryRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(repoRes, HttpStatus.OK));

        // Test
        Optional<Repository> repository = gitHubProvider.getRepository(repoId, null);

        // Verify
        assertThat(repository).isPresent();
        assertThat(repository.get().getName()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repositories/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetRepositoryRepositoryRes.class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledThenAssertCommitsReturned() throws Exception {
        // Load JSON responses
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        GitHubListCommitsCommitRes[] commitsRes = loadJson("github/list_commits.json", GitHubListCommitsCommitRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setId("342219496");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getOrganization call (called internally by listCommits)
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listCommits
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // Test
        Page<Commit> commits = gitHubProvider.listCommits(repository, CommitListNoFilter.getInstance(), pageable);

        // Verify
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledWithAccountOwnerThenAssertCommitsReturned() throws Exception {
        // Load JSON responses
        GitHubGetCurrentUserUserRes userRes = loadJson("github/get_current_user.json", GitHubGetCurrentUserUserRes.class);
        GitHubListCommitsCommitRes[] commitsRes = loadJson("github/list_commits.json", GitHubListCommitsCommitRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setId("342219496");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ACCOUNT);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getCurrentUser call (called internally by listCommits)
        when(restTemplate.exchange(
                eq(baseUrl + "/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetCurrentUserUserRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listCommits
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // Test
        Page<Commit> commits = gitHubProvider.listCommits(repository, CommitListNoFilter.getInstance(), pageable);

        // Verify
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListBranchesCalledThenAssertBranchesReturned() throws Exception {
        // Load JSON responses
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        GitHubListBranchesBranchRes[] branchesRes = loadJson("github/list_branches.json", GitHubListBranchesBranchRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getOrganization call (called internally by listBranches)
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listBranches
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListBranchesBranchRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(branchesRes, HttpStatus.OK));

        // Test
        Page<Branch> branches = gitHubProvider.listBranches(repository, pageable);

        // Verify
        assertThat(branches).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListBranchesBranchRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListBranchesCalledWithAccountOwnerThenAssertBranchesReturned() throws Exception {
        // Load JSON responses
        GitHubGetCurrentUserUserRes userRes = loadJson("github/get_current_user.json", GitHubGetCurrentUserUserRes.class);
        GitHubListBranchesBranchRes[] branchesRes = loadJson("github/list_branches.json", GitHubListBranchesBranchRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ACCOUNT);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getCurrentUser call (called internally by listBranches)
        when(restTemplate.exchange(
                eq(baseUrl + "/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetCurrentUserUserRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listBranches
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListBranchesBranchRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(branchesRes, HttpStatus.OK));

        // Test
        Page<Branch> branches = gitHubProvider.listBranches(repository, pageable);

        // Verify
        assertThat(branches).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListBranchesBranchRes[].class),
                anyMap()
        );
    }


    @Test
    void whenListTagsCalledThenAssertTagsReturned() throws Exception {
        // Load JSON responses
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        GitHubListTagsTagRes[] tagsRes = loadJson("github/list_tags.json", GitHubListTagsTagRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getOrganization call (called internally by listTags)
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listTags
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListTagsTagRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(tagsRes, HttpStatus.OK));

        // Test
        Page<Tag> tags = gitHubProvider.listTags(repository, pageable);

        // Verify
        assertThat(tags).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListTagsTagRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListTagsCalledWithAccountOwnerThenAssertTagsReturned() throws Exception {
        // Load JSON responses
        GitHubGetCurrentUserUserRes userRes = loadJson("github/get_current_user.json", GitHubGetCurrentUserUserRes.class);
        GitHubListTagsTagRes[] tagsRes = loadJson("github/list_tags.json", GitHubListTagsTagRes[].class);
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ACCOUNT);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock getCurrentUser call (called internally by listTags)
        when(restTemplate.exchange(
                eq(baseUrl + "/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetCurrentUserUserRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));
        
        // Mock RestTemplate response for listTags
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListTagsTagRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(tagsRes, HttpStatus.OK));

        // Test
        Page<Tag> tags = gitHubProvider.listTags(repository, pageable);

        // Verify
        assertThat(tags).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListTagsTagRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledWithCommitHashFiltersThenAssertFilteredCommitsReturned() throws Exception {
        GitHubCompareCommitsRes filteredCommitsRes = loadJson("github/list_commits_filtered.json", GitHubCompareCommitsRes.class);
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setId("342219496");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        Pageable pageable = PageRequest.of(0, 20);

        // Mock getOrganization call (called internally by listCommits)
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        
        // Mock RestTemplate response for compare endpoint (used when filters are provided)
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/compare/{from}...{to}?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubCompareCommitsRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(filteredCommitsRes, HttpStatus.OK));

        CommitListFilter filters = new CommitListRangeFilter(new CommitRefHash("commit1"), new CommitRefHash("commit2"));

        // Test
        Page<Commit> commits = gitHubProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("commit2", "Second commit message", "Carol Example", "carol@example.com", Date.from(Instant.parse("2025-11-20T13:05:02Z"))));
        expectedCommits.add(new Commit("commit1", "First commit message", "Bob Example", "bob@example.com", Date.from(Instant.parse("2025-11-20T11:40:18Z"))));

        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(expectedCommits.size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "owner", "test-org",
                "repo", "test-repo",
                "from", "commit1",
                "to", "commit2",
                "page", 1,
                "perPage", 20
        );

        // Verify that the compare endpoint was called (not the regular commits endpoint)
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/compare/{from}...{to}?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubCompareCommitsRes.class),
                eq(queryParams)
        );
    }

    /**
     * Helper method to load JSON from test resources and deserialize it
     */
    private <T> T loadJson(String resourcePath, Class<T> clazz) throws Exception {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        return objectMapper.readValue(inputStream, clazz);
    }

    @Test
    void whenListCommitsFilteredByBranchNameThenAssertCommitsReturned() throws Exception {
        GitHubListCommitsCommitRes[] commitsRes = loadJson("github/list_commit_by_branch_name/list_commit_by_branch_name.json", GitHubListCommitsCommitRes[].class);
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setId("342219496");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        Pageable pageable = PageRequest.of(0, 20);

        // Mock getOrganization call (called internally by listCommits)
        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?sha={branchName}&page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        CommitListFilter filters = new CommitListSingleBranchFilter(new CommitRefBranch("test"));

        // Test
        Page<Commit> commits = gitHubProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "commit message 1", "User Name", "user@example.com", Date.from(Instant.parse("2026-02-19T16:50:18Z"))));

        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(expectedCommits.size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "owner", "test-org",
                "repo", "test-repo",
                "branchName", "test",
                "page", 1,
                "perPage", 20
        );

        // Verify that the list commits endpoint was called (not the regular commits endpoint)
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/commits?sha={branchName}&page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubListCommitsCommitRes[].class),
                eq(queryParams)
        );
    }

    /*
     * Scenario Outline: PR-001 Create a Pull Request with an explicit target branch
     *   Given a valid GitHub repository
     *   And a CreatePullRequest with source "update-v2", target "main", title "Update v2", and body "Generated update"
     *   And the source branch has already been pushed
     *   When createPullRequest is called
     *   Then the GitHub create Pull Request endpoint is called once with the expected authenticated POST request
     *   And the provider payload maps source, target, title, and body correctly
     *   And the returned PullRequest contains non-blank id and webUrl
     *   And the returned source branch, target branch, title, body, and state are mapped correctly
     *   And no Git push, merge, or branch deletion is performed
     */
    @Test
    void pr001_createPullRequestWithExplicitTarget() throws Exception {
        GitHubCreatePullRequestRes prRes = loadJson("github/create_pull_request.json", GitHubCreatePullRequestRes.class);
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);

        Repository repository = githubRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", "main", "Update v2", "Generated update");

        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(prRes, HttpStatus.CREATED));

        PullRequest result = gitHubProvider.createPullRequest(repository, request);

        assertThat(result.getId()).isEqualTo("42");
        assertThat(result.getWebUrl()).isEqualTo("https://github.com/test-org/test-repo/pull/42");
        assertThat(result.getSourceBranch()).isEqualTo("update-v2");
        assertThat(result.getTargetBranch()).isEqualTo("main");
        assertThat(result.getTitle()).isEqualTo("Update v2");
        assertThat(result.getBody()).isEqualTo("Generated update");
        assertThat(result.getState()).isEqualTo("open");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        );
        GitHubCreatePullRequestReq body = (GitHubCreatePullRequestReq) entityCaptor.getValue().getBody();
        assertThat(body.getHead()).isEqualTo("update-v2");
        assertThat(body.getBase()).isEqualTo("main");
        assertThat(body.getTitle()).isEqualTo("Update v2");
        assertThat(body.getBody()).isEqualTo("Generated update");
    }

    /*
     * Scenario Outline: PR-002 Default the target branch from the repository
     *   Given a valid GitHub repository whose default branch is "main"
     *   And a CreatePullRequest with source "update-v2", blank target, and title "Update v2"
     *   When createPullRequest is called
     *   Then the provider request uses "main" as the target branch
     *   And the returned PullRequest has target branch "main"
     */
    @Test
    void pr002_defaultTargetBranchFromRepository() throws Exception {
        GitHubCreatePullRequestRes prRes = loadJson("github/create_pull_request.json", GitHubCreatePullRequestRes.class);
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        Repository repository = githubRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", null, "Update v2", null);

        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(prRes, HttpStatus.CREATED));

        PullRequest result = gitHubProvider.createPullRequest(repository, request);

        assertThat(result.getTargetBranch()).isEqualTo("main");
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        );
        assertThat(((GitHubCreatePullRequestReq) entityCaptor.getValue().getBody()).getBase()).isEqualTo("main");
    }

    /*
     * Scenario Outline: PR-003 Reject invalid Pull Request input before HTTP
     *   Given a valid GitHub instance
     *   And <invalid input>
     *   When createPullRequest is called
     *   Then an IllegalArgumentException describing the invalid field is thrown
     *   And the provider HTTP API is not called
     */
    @ParameterizedTest
    @CsvSource({
            "nullRepo, update-v2, main, Update v2",
            "blankSource, , main, Update v2",
            "blankTitle, update-v2, main, ",
            "blankDefault, update-v2, , Update v2",
            "sameBranches, main, main, Update v2"
    })
    void pr003_rejectInvalidPullRequestInputBeforeHttp(String caseName, String source, String target, String title) {
        Repository repository = "nullRepo".equals(caseName) ? null : githubRepository();
        if (repository != null && "blankDefault".equals(caseName)) {
            repository.setDefaultBranch(null);
        }
        CreatePullRequest request = new CreatePullRequest(source, target, title, null);

        assertThatThrownBy(() -> gitHubProvider.createPullRequest(repository, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class), anyMap());
    }

    /*
     * Scenario Outline: PR-004 Map provider authentication failure
     *   Given a valid GitHub repository and CreatePullRequest
     *   And the provider create Pull Request API returns HTTP 401
     *   When createPullRequest is called
     *   Then GitProviderAuthenticationException is thrown
     */
    @Test
    void pr004_mapProviderAuthenticationFailure() throws Exception {
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        Repository repository = githubRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", "main", "Update v2", null);

        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> gitHubProvider.createPullRequest(repository, request))
                .isInstanceOf(GitProviderAuthenticationException.class);
    }

    /*
     * Scenario Outline: PR-005 Preserve provider HTTP errors
     *   Given a valid GitHub repository and CreatePullRequest
     *   And the provider create Pull Request API returns a non-401 error with status and response body
     *   When createPullRequest is called
     *   Then GitClientException preserves the provider status and response body
     */
    @Test
    void pr005_preserveProviderHttpErrors() throws Exception {
        GitHubGetOrganizationOrganizationRes orgRes = loadJson("github/get_organization.json", GitHubGetOrganizationOrganizationRes.class);
        Repository repository = githubRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", "main", "Update v2", null);

        when(restTemplate.exchange(
                eq(baseUrl + "/orgs/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitHubGetOrganizationOrganizationRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(orgRes, HttpStatus.OK));
        when(restTemplate.exchange(
                eq(baseUrl + "/repos/{owner}/{repo}/pulls"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitHubCreatePullRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Validation Failed",
                org.springframework.http.HttpHeaders.EMPTY, "{\"message\":\"Validation Failed\"}".getBytes(), null));

        assertThatThrownBy(() -> gitHubProvider.createPullRequest(repository, request))
                .isInstanceOf(GitClientException.class)
                .satisfies(ex -> {
                    GitClientException gitEx = (GitClientException) ex;
                    assertThat(gitEx.getCode()).isEqualTo(422);
                    assertThat(gitEx.getResponseBody()).contains("Validation Failed");
                });
    }

    private Repository githubRepository() {
        Repository repository = new Repository();
        repository.setName("test-repo");
        repository.setId("342219496");
        repository.setOwnerId("test-org");
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        repository.setDefaultBranch("main");
        return repository;
    }
}

