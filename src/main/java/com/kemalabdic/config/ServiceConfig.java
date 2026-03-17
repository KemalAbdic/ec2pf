package com.kemalabdic.config;

public record ServiceConfig(String name, int localPort, boolean skip, int remotePort) {
}
