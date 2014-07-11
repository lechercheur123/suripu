package com.hello.suripu.core.oauth;

import com.google.common.base.Optional;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.AbstractHttpContextInjectable;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;
import com.yammer.dropwizard.auth.AuthenticationException;
import com.yammer.dropwizard.auth.Authenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.lang.reflect.Type;

public class OAuthProvider<T> implements InjectableProvider<Scope, Type> {

    private static class OAuthWithScopeInjectable<T> extends AbstractHttpContextInjectable<T> {
            private static final Logger LOGGER = LoggerFactory.getLogger(OAuthWithScopeInjectable.class);
            private static final String HEADER_NAME = "WWW-Authenticate";
            private static final String HEADER_VALUE = "Bearer realm=\"%s\"";

            private final Authenticator<ClientCredentials, T> authenticator;
            private final String realm;
            private final OAuthScope[] scopes;

            private OAuthWithScopeInjectable(Authenticator<ClientCredentials, T> authenticator, String realm, OAuthScope[] scopes) {
                this.authenticator = authenticator;
                this.realm = realm;
                this.scopes = scopes;
            }

            @Override
            public T getValue(HttpContext c) {
                final String header = c.getRequest().getHeaderValue(HttpHeaders.AUTHORIZATION);
                Optional<String> bearerString = Util.extractBearerToken(header);
                if (bearerString.isPresent()) {
                    try {
                        final ClientCredentials creds = new ClientCredentials(scopes, bearerString.get());
                        final Optional<T> result = authenticator.authenticate(creds);
                        if (result.isPresent()) {
                            return result.get();
                        }
                    } catch (AuthenticationException e) {
                        LOGGER.warn("Error authenticating credentials", e);
                        throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
                    }
                }

                for(final OAuthScope scope : scopes) {
                    if(scope == OAuthScope.ADMINISTRATION_READ ||
                       scope == OAuthScope.ADMINISTRATION_WRITE ||
                       scope == OAuthScope.API_INTERNAL_DATA_READ ||
                       scope == OAuthScope.API_INTERNAL_DATA_WRITE) {

                        final String oauthTokenMaybe = (bearerString.isPresent()) ? bearerString.get() : "[NO TOKEN]";
                        LOGGER.warn("Attempt to access admin protected resource with bad/wrong oauth token");
                        LOGGER.warn("OAuth token was: {} and request was {}", oauthTokenMaybe , c.getRequest().getAbsolutePath());
                        LOGGER.warn("Voluntarily returning a 404 to not expose too much to the client");
                        throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
                    }
                }

                throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).build());


            }
        }

        private final Authenticator<ClientCredentials, T> authenticator;
        private final String realm;

        /**
         * Creates a new OAuthProvider with the given {@link Authenticator} and realm.
         *
         * @param authenticator the authenticator which will take the OAuth2 bearer token and convert
         * them into instances of {@code T}
         * @param realm the name of the authentication realm
         */
        public OAuthProvider(Authenticator<ClientCredentials, T> authenticator, String realm) {
            this.authenticator = authenticator;
            this.realm = realm;
        }

        @Override
        public ComponentScope getScope() {
            return ComponentScope.PerRequest;
        }

        @Override
        public Injectable<?> getInjectable(ComponentContext ic,
                                           Scope a,
                                           Type c) {
            return new OAuthWithScopeInjectable<T>(authenticator, realm, a.value());
        }
    }
