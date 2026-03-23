package fhcampus.nilspetsch.tdms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tdms.synthetic-python")
public class SyntheticPythonServiceProperties {

    private String baseUrl = "http://127.0.0.1:8000";
    private boolean enabled = true;
    private boolean persistRemoteModel = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPersistRemoteModel() {
        return persistRemoteModel;
    }

    public void setPersistRemoteModel(boolean persistRemoteModel) {
        this.persistRemoteModel = persistRemoteModel;
    }
}
