package com.airbnb.skipper.testutils;

import com.airbnb.skipper.RequestContextSerde;
import java.nio.charset.StandardCharsets;

/**
 * OSS-safe {@link RequestContextSerde} for Skipper tests.
 *
 * <p>Round-trips a {@link TestRequestContext} through storage by encoding its {@code userId} as
 * UTF-8 bytes. This lets propagation tests verify that the context persisted with a waiting
 * workflow is faithfully reconstructed when the scheduler reloads and resumes it.
 *
 * <p>Contract per {@link RequestContextSerde}: {@code serialize} tolerates {@code null} and
 * unexpected payload types by emitting empty bytes; {@code deserialize} of empty bytes reads back a
 * context with a {@code null} userId (so callers still get a usable {@link TestRequestContext}).
 */
public final class TestRequestContextSerde implements RequestContextSerde {

  @Override
  public byte[] serialize(Object ctx) {
    if (ctx instanceof TestRequestContext) {
      String userId = ((TestRequestContext) ctx).getUserId();
      if (userId != null) {
        return userId.getBytes(StandardCharsets.UTF_8);
      }
    }
    return new byte[0];
  }

  @Override
  public Object deserialize(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return TestRequestContext.builder().build();
    }
    return TestRequestContext.builder().userId(new String(bytes, StandardCharsets.UTF_8)).build();
  }
}
