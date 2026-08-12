package io.casehub.examples.helpdesk;

import io.quarkus.test.junit.QuarkusTestProfile;

public class DemoTestProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
        return "demo";
    }
}
