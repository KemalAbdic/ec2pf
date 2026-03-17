package com.kemalabdic.config;

import java.nio.file.Path;
import java.util.List;

public record PortForwardConfig(AwsConfig awsConfig, List<ServiceConfig> services, Path configFilePath, String configLabel) {
}
