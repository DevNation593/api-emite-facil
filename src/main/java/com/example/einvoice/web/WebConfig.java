package com.example.einvoice.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.einvoice.identity.UserIdentityResolver;
import com.example.einvoice.tenancy.TenantAccessResolver;

/** Registra los resolvers que entregan a los controladores la identidad y el tenant verificados. */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

	private final UserIdentityResolver userIdentityResolver;

	private final TenantAccessResolver tenantAccessResolver;

	public WebConfig(UserIdentityResolver userIdentityResolver, TenantAccessResolver tenantAccessResolver) {
		this.userIdentityResolver = userIdentityResolver;
		this.tenantAccessResolver = tenantAccessResolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(userIdentityResolver);
		resolvers.add(tenantAccessResolver);
	}

}
