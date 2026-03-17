package com.kemalabdic.aws;

import java.io.IOException;
import java.util.List;

public interface AwsCliExecutor {

  Process execute(List<String> command) throws IOException;

  Process executeDetached(List<String> command) throws IOException;
}
