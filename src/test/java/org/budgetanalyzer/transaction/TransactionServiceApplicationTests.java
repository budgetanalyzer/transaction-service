package org.budgetanalyzer.transaction;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest
class TransactionServiceApplicationTests {

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
      var mockDecoder = mock(JwtDecoder.class);
      var mockJwt =
          Jwt.withTokenValue("test-token")
              .header("alg", "RS256")
              .header("typ", "JWT")
              .claim("sub", "test-user")
              .claim("scope", "openid profile email")
              .claim("aud", "https://test-api.example.com")
              .claim("iss", "https://test-issuer.example.com/")
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(3600))
              .build();

      when(mockDecoder.decode(anyString())).thenReturn(mockJwt);
      return mockDecoder;
    }
  }

  @Test
  void contextLoads() {}
}
