package org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GitLabListOrganizationsGroupRes {
    private long id;
    private String name;
    @JsonProperty("full_path")
    private String fullPath;
    @JsonProperty("web_url")
    private String webUrl;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }
}

