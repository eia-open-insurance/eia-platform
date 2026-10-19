package com.eia.platform.libs;

public record ApiError(String code, String message, String correlationId) {}
