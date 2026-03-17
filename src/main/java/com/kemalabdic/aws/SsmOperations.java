package com.kemalabdic.aws;

import com.kemalabdic.config.AwsConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SsmOperations {

  Map<String, String> batchLookupInstanceIds(List<String> serviceNames, AwsConfig config);

  Optional<String> lookupInstanceId(String serviceName, AwsConfig config);

  Optional<Long> startSession(int localPort, int remotePort, String instanceId, AwsConfig config);
}
