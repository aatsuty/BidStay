package com.bidstay.auth;

import com.bidstay.common.ApiException;
import com.bidstay.user.AccessService;
import com.bidstay.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Component
@RequiredArgsConstructor
class CallerIdResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final AuthService auth;
    private final AccessService access;
    private final Environment environment;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CallerId.class) && parameter.getParameterType() == Long.class;
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        Long id = authorization == null
                ? demoUserId(request.getHeader("X-User-Id"))
                : auth.sessionUserId(authorization);
        if (parameter.getParameterAnnotation(CallerId.class).guestOnly() && access.user(id).getRole() != Role.GUEST) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Guest accounts only");
        }
        return id;
    }

    private Long demoUserId(String value) {
        if (value != null && environment.acceptsProfiles(Profiles.of("dev"))) {
            try {
                long id = Long.parseLong(value);
                if (id > 0) return id;
            } catch (NumberFormatException ignored) {
                // Invalid demo IDs are treated like missing credentials.
            }
        }
        throw AuthService.signInRequired();
    }
}
