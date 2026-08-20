package lk.ijse.eca.apigateway.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
public class RouteValidator {

    public static final List<String> openApiEndpoints = List.of(
            "/api/v1/members/login",
            "/api/v1/members/refresh"
    );

    public Predicate<ServerHttpRequest> isSecured =
            request -> {
                String path = request.getURI().getPath();
                String method = request.getMethod().name();
                
                // Allow public access to uploaded profile images
                if (path.startsWith("/api/v1/members/uploads/")) {
                    return false;
                }
                
                // Allow login and refresh endpoints
                if (path.equals("/api/v1/members/login") || path.equals("/api/v1/members/refresh")) {
                    return false;
                }
                
                // Allow member registration (POST /api/v1/members)
                if (path.equals("/api/v1/members") && method.equalsIgnoreCase("POST")) {
                    return false;
                }
                
                // Allow eureka path
                if (path.contains("/eureka")) {
                    return false;
                }
                
                return true;
            };
}
