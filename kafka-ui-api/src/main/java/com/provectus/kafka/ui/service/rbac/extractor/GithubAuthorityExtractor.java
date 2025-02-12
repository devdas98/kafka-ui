package com.provectus.kafka.ui.service.rbac.extractor;

import static com.provectus.kafka.ui.model.rbac.provider.Provider.Name.GITHUB;

import com.provectus.kafka.ui.model.rbac.Role;
import com.provectus.kafka.ui.model.rbac.provider.Provider;
import com.provectus.kafka.ui.service.rbac.AccessControlService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
public class GithubAuthorityExtractor implements ProviderAuthorityExtractor {

  private static final String ORGANIZATION_ATTRIBUTE_NAME = "organizations_url";
  private static final String USERNAME_ATTRIBUTE_NAME = "login";
  private static final String ORGANIZATION_NAME = "login";
  private static final String GITHUB_ACCEPT_HEADER = "application/vnd.github+json";
  private static final String DUMMY = "dummy";
  // The number of results (max 100) per page of list organizations for authenticated user.
  private static final Integer ORGANIZATIONS_PER_PAGE = 100;

  @Override
  public boolean isApplicable(String provider, Map<String, String> customParams) {
    return GITHUB.equalsIgnoreCase(provider) || GITHUB.equalsIgnoreCase(customParams.get(TYPE));
  }

  @Override
  public Mono<Set<String>> extract(AccessControlService acs, Object value, Map<String, Object> additionalParams) {
    DefaultOAuth2User principal;
    try {
      principal = (DefaultOAuth2User) value;
    } catch (ClassCastException e) {
      log.error("Can't cast value to DefaultOAuth2User", e);
      throw new RuntimeException();
    }

    Set<String> groupsByUsername = new HashSet<>();
    String username = principal.getAttribute(USERNAME_ATTRIBUTE_NAME);
    if (username == null) {
      log.debug("Github username param is not present");
    } else {
      acs.getRoles()
          .stream()
          .filter(r -> r.getSubjects()
              .stream()
              .filter(s -> s.getProvider().equals(Provider.OAUTH_GITHUB))
              .filter(s -> s.getType().equals("user"))
              .anyMatch(s -> s.getValue().equals(username)))
          .map(Role::getName)
          .forEach(groupsByUsername::add);
    }

    String organization = principal.getAttribute(ORGANIZATION_ATTRIBUTE_NAME);
    if (organization == null) {
      log.debug("Github organization param is not present");
      return Mono.just(groupsByUsername);
    }

    OAuth2UserRequest req = (OAuth2UserRequest) additionalParams.get("request");
    String infoEndpoint = req.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri();

    if (infoEndpoint == null) {
      infoEndpoint = CommonOAuth2Provider.GITHUB
          .getBuilder(DUMMY)
          .clientId(DUMMY)
          .build()
          .getProviderDetails()
          .getUserInfoEndpoint()
          .getUri();
    }

    WebClient webClient = WebClient.create(infoEndpoint);

    final Mono<List<Map<String, Object>>> userOrganizations = webClient
        .get()
        .uri(uriBuilder -> uriBuilder.path("/orgs")
            .queryParam("per_page", ORGANIZATIONS_PER_PAGE)
            .build())
        .headers(headers -> {
          headers.set(HttpHeaders.ACCEPT, GITHUB_ACCEPT_HEADER);
          OAuth2UserRequest request = (OAuth2UserRequest) additionalParams.get("request");
          headers.setBearerAuth(request.getAccessToken().getTokenValue());
        })
        .retrieve()
        //@formatter:off
        .bodyToMono(new ParameterizedTypeReference<>() {});
    //@formatter:on

    return userOrganizations
        .map(orgsMap -> {
          var groupsByOrg = acs.getRoles()
              .stream()
              .filter(role -> role.getSubjects()
                  .stream()
                  .filter(s -> s.getProvider().equals(Provider.OAUTH_GITHUB))
                  .filter(s -> s.getType().equals("organization"))
                  .anyMatch(subject -> orgsMap.stream()
                      .map(org -> org.get(ORGANIZATION_NAME).toString())
                      .distinct()
                      .anyMatch(orgName -> orgName.equalsIgnoreCase(subject.getValue()))
                  ))
              .map(Role::getName);

          return Stream.concat(groupsByOrg, groupsByUsername.stream()).collect(Collectors.toSet());
        });
  }

}
