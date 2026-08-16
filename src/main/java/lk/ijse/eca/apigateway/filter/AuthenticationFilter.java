package lk.ijse.eca.apigateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.ijse.eca.apigateway.dto.response.ApiResponse;
import lk.ijse.eca.apigateway.util.JwtUtil;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private final RouteValidator validator;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(RouteValidator validator, JwtUtil jwtUtil, ObjectMapper objectMapper) {
        super(Config.class);
        this.validator = validator;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // SECURITY: Always strip X-User-* headers from incoming requests
            // to prevent clients from spoofing identity headers.
            ServerHttpRequest sanitizedRequest = request.mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Email");
                        headers.remove("X-User-Role");
                    })
                    .build();
            exchange = exchange.mutate().request(sanitizedRequest).build();
            request = exchange.getRequest();

            if (validator.isSecured.test(request)) {
                // Check if Authorization header is present and valid
                String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (authHeader == null) {
                    return onError(exchange, "Authorization header is missing", HttpStatus.UNAUTHORIZED);
                }

                if (!authHeader.startsWith("Bearer ")) {
                    return onError(exchange, "Invalid Authorization header format", HttpStatus.UNAUTHORIZED);
                }

                String token = authHeader.substring(7);
                try {
                    String email = jwtUtil.extractEmail(token);
                    if (email == null || jwtUtil.isTokenExpired(token)) {
                        return onError(exchange, "JWT token has expired or is invalid", HttpStatus.UNAUTHORIZED);
                    }

                    Long id = jwtUtil.extractId(token);
                    String role = jwtUtil.extractRole(token);

                    // Mutate request to inject custom headers
                    ServerHttpRequest mutatedRequest = request.mutate()
                            .header("X-User-Id", id != null ? String.valueOf(id) : "")
                            .header("X-User-Email", email != null ? email : "")
                            .header("X-User-Role", role != null ? role : "")
                            .build();

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());

                } catch (Exception e) {
                    String errorMsg = "Invalid JWT token";
                    if (e instanceof io.jsonwebtoken.ExpiredJwtException) {
                        errorMsg = "JWT token has expired";
                    } else if (e instanceof io.jsonwebtoken.security.SignatureException || e instanceof io.jsonwebtoken.security.SecurityException) {
                        errorMsg = "JWT signature validation failed";
                    } else if (e instanceof io.jsonwebtoken.MalformedJwtException) {
                        errorMsg = "Malformed JWT token";
                    }
                    return onError(exchange, errorMsg, HttpStatus.UNAUTHORIZED);
                }
            }
            return chain.filter(exchange);
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .success(false)
                .data(ApiResponse.DataWrapper.<Void>builder()
                        .message(message)
                        .error(status.getReasonPhrase())
                        .build())
                .status(status.value())
                .path(exchange.getRequest().getURI().getPath())
                .timestamp(LocalDateTime.now())
                .build();

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(apiResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    public static class Config {
        // Configuration fields
    }
}
