/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 基于 kkzxak47/keycloak-services-social-wechatwork 开源代码改造
 * 改造内容：
 *   1. javax.ws.rs -> jakarta.ws.rs (Keycloak 26 / Jakarta EE 迁移)
 *   2. cgi-bin/user/getuserinfo -> cgi-bin/auth/getuserinfo (企业微信新版 API)
 *   3. 错误码判断逻辑改进，增加 40029 重试
 */
package org.keycloak.social.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

public class WechatWorkIdentityProvider
    extends AbstractOAuth2IdentityProvider<WechatWorkProviderConfig>
    implements SocialIdentityProvider<WechatWorkProviderConfig> {

  public static final String AUTH_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
  public static final String QRCODE_AUTH_URL =
      "https://open.work.weixin.qq.com/wwopen/sso/qrConnect"; // 企业微信外使用
  public static final String TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

  public static final String DEFAULT_SCOPE = "snsapi_base";
  public static final String DEFAULT_RESPONSE_TYPE = "code";
  public static final String WEIXIN_REDIRECT_FRAGMENT = "wechat_redirect";

  // ★★★ 核心修复：使用企业微信新版 API ★★★
  // 旧接口 cgi-bin/user/getuserinfo 已被企业微信废弃，
  // 新接口 cgi-bin/auth/getuserinfo 才能正确处理扫码登录产生的 code
  public static final String PROFILE_URL = "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo";
  public static final String PROFILE_DETAIL_URL = "https://qyapi.weixin.qq.com/cgi-bin/user/get";

  public static final String OAUTH2_PARAMETER_CLIENT_ID = "appid";
  public static final String OAUTH2_PARAMETER_AGENT_ID = "agentid";
  public static final String OAUTH2_PARAMETER_RESPONSE_TYPE = "response_type";

  public static final String WEIXIN_CORP_ID = "corpid";
  public static final String WEIXIN_CORP_SECRET = "corpsecret";
  public static final String PROFILE_MOBILE = "mobile";
  public static final String PROFILE_GENDER = "gender";
  public static final String PROFILE_STATUS = "status";
  public static final String PROFILE_ENABLE = "enable";
  public static final String PROFILE_USERID = "userid";

  private final String ACCESS_TOKEN_KEY = "access_token";
  private final String ACCESS_TOKEN_CACHE_KEY = "wechat_work_sso_access_token";

  private static final DefaultCacheManager cacheManager = new DefaultCacheManager();
  private static final String WECHAT_WORK_CACHE_NAME = "wechat_work_sso";
  private static final ConcurrentMap<String, Cache<String, String>> caches =
      new ConcurrentHashMap<>();

  private static Cache<String, String> createCache(String suffix) {
    try {
      String cacheName = WECHAT_WORK_CACHE_NAME + ":" + suffix;

      ConfigurationBuilder config = new ConfigurationBuilder();
      cacheManager.defineConfiguration(cacheName, config.build());

      Cache<String, String> cache = cacheManager.getCache(cacheName);
      logger.info(cache);
      return cache;
    } catch (Exception e) {
      logger.error(e);
      e.printStackTrace(System.out);
      throw e;
    }
  }

  private Cache<String, String> getCache() {
    return caches.computeIfAbsent(
        getConfig().getClientId() + ":" + getConfig().getAgentId(),
        WechatWorkIdentityProvider::createCache);
  }

  private String getAccessToken() {
    try {
      String token = getCache().get(ACCESS_TOKEN_CACHE_KEY);
      if (token == null) {
        JsonNode j = renewAccessToken();
        if (j == null) {
          j = renewAccessToken();
          if (j == null) {
            throw new Exception("renew access token error");
          }
          logger.debug("retry in renew access token " + j);
        }
        token = getJsonProperty(j, ACCESS_TOKEN_KEY);
        long timeout = Integer.parseInt(getJsonProperty(j, "expires_in"));
        getCache().put(ACCESS_TOKEN_CACHE_KEY, token, timeout, TimeUnit.SECONDS);
      }
      return token;
    } catch (Exception e) {
      logger.error(e);
      e.printStackTrace(System.out);
    }
    return null;
  }

  private JsonNode renewAccessToken() {
    try {
      JsonNode result = SimpleHttp.doGet(TOKEN_URL, session)
          .param(WEIXIN_CORP_ID, getConfig().getClientId())
          .param(WEIXIN_CORP_SECRET, getConfig().getClientSecret())
          .asJson();
      logger.info("renewAccessToken response: " + result.toString());
      return result;
    } catch (Exception e) {
      logger.error(e);
      e.printStackTrace(System.out);
    }
    return null;
  }

  private String resetAccessToken() {
    getCache().remove(ACCESS_TOKEN_CACHE_KEY);
    return getAccessToken();
  }

  public WechatWorkIdentityProvider(KeycloakSession session, WechatWorkProviderConfig config) {
    super(session, config);
    config.setAuthorizationUrl(AUTH_URL);
    config.setQrcodeAuthorizationUrl(QRCODE_AUTH_URL);
    config.setTokenUrl(TOKEN_URL);
  }

  @Override
  public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
    logger.info("WechatWorkIdentityProvider.callback invoked! Returning custom Endpoint.");
    return new Endpoint(callback, realm, event);
  }

  @Override
  protected boolean supportsExternalExchange() {
    return true;
  }

  @Override
  protected BrokeredIdentityContext extractIdentityFromProfile(
      EventBuilder event, JsonNode profile) {
    logger.info(profile.toString());
    // profile: see https://developer.work.weixin.qq.com/document/path/90196
    BrokeredIdentityContext identity =
        new BrokeredIdentityContext(getJsonProperty(profile, "userid"), getConfig());

    identity.setUsername(getJsonProperty(profile, "userid").toLowerCase());
    identity.setBrokerUserId(getJsonProperty(profile, "userid").toLowerCase());
    identity.setModelUsername(getJsonProperty(profile, "userid").toLowerCase());
    String userId = getJsonProperty(profile, "userid").toLowerCase();
    String email = getJsonProperty(profile, "biz_mail");
    if (email == null || email.length() == 0) {
      email = getJsonProperty(profile, "email");
    }
    if (email == null || email.length() == 0) {
      email = userId + "@wecom.local";
    }
    identity.setFirstName(getJsonProperty(profile, "name"));
    if (email != null && email.contains("@")) {
      identity.setLastName(email.split("@")[0].toLowerCase());
    } else {
      identity.setLastName(userId);
    }
    identity.setEmail(email);
    // 手机号码，第三方仅通讯录应用可获取
    identity.setUserAttribute(PROFILE_MOBILE, getJsonProperty(profile, "mobile"));
    // 性别。0表示未定义，1表示男性，2表示女性
    identity.setUserAttribute(PROFILE_GENDER, getJsonProperty(profile, "gender"));
    // 激活状态: 1=已激活，2=已禁用，4=未激活。
    identity.setUserAttribute(PROFILE_STATUS, getJsonProperty(profile, "status"));
    // 成员启用状态。1表示启用的成员，0表示被禁用。
    identity.setUserAttribute(PROFILE_ENABLE, getJsonProperty(profile, "enable"));
    identity.setUserAttribute(PROFILE_USERID, getJsonProperty(profile, "userid"));

    AbstractJsonUserAttributeMapper.storeUserProfileForMapper(
        identity, profile, getConfig().getAlias());
    return identity;
  }

  public BrokeredIdentityContext getFederatedIdentity(String garbageResponse) {
    String authorizationCode = session.getContext().getUri().getQueryParameters().getFirst("code");
    if (authorizationCode == null || authorizationCode.isEmpty()) {
        throw new IdentityBrokerException("No code parameter found in request");
    }
    
    String accessToken = getAccessToken();
    if (accessToken == null) {
      throw new IdentityBrokerException("No access token available in OAuth server response");
    }

    BrokeredIdentityContext context = null;
    try {
      logger.info("getFederatedIdentity called with real code: " + authorizationCode);
      JsonNode profile;
      profile =
          SimpleHttp.doGet(PROFILE_URL, session)
              .param(ACCESS_TOKEN_KEY, accessToken)
              .param("code", authorizationCode)
              .asJson();
      logger.info("profile in federation " + profile.toString());
      long errorCode = profile.get("errcode").asInt();

      // 42001: access_token已过期, 40014: 不合法的access_token, 40029: invalid code
      if (errorCode == 42001 || errorCode == 40014 || errorCode == 40029) {
        accessToken = resetAccessToken();
        profile =
            SimpleHttp.doGet(PROFILE_URL, session)
                .param(ACCESS_TOKEN_KEY, accessToken)
                .param("code", authorizationCode)
                .asJson();
        logger.info("profile retried " + profile.toString());
        errorCode = profile.get("errcode").asInt();
      }
      if (errorCode != 0) {
        throw new IdentityBrokerException(
            "get user info failed, errcode=" + errorCode
            + ", errmsg=" + getJsonProperty(profile, "errmsg"));
      }

      // 新版 API 返回的 userid 字段名可能是小写 "userid" 而非 "UserId"
      String userId = getJsonProperty(profile, "userid");
      if (userId == null || userId.isEmpty()) {
        userId = getJsonProperty(profile, "UserId");
      }

      profile =
          SimpleHttp.doGet(PROFILE_DETAIL_URL, session)
              .param(ACCESS_TOKEN_KEY, accessToken)
              .param("userid", userId)
              .asJson();
      logger.info("user detail " + profile.toString());
      context = extractIdentityFromProfile(null, profile);
      context.getContextData().put(FEDERATED_ACCESS_TOKEN, accessToken);
    } catch (Exception e) {
      logger.error(e);
      e.printStackTrace(System.out);
    }
    return context;
  }

  @Override
  protected String getDefaultScopes() {
    return DEFAULT_SCOPE;
  }

  @Override
  protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {

    final UriBuilder uriBuilder;

    String ua =
        request.getHttpRequest().getHttpHeaders().getHeaderString("user-agent").toLowerCase();
    logger.info("Creating Auth Url...");
    logger.info("构建授权链接。user-agent=" + ua + ", request=" + request);
    if (ua.contains("wxwork")) {
      uriBuilder = UriBuilder.fromUri(getConfig().getAuthorizationUrl());
      uriBuilder
          .queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
          .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
          .queryParam(OAUTH2_PARAMETER_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE)
          .queryParam(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
          .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded());
      uriBuilder.fragment(WEIXIN_REDIRECT_FRAGMENT);
      logger.info("企业微信内部浏览器，构建网页授权链接");
    } else {
      uriBuilder = UriBuilder.fromUri(getConfig().getQrcodeAuthorizationUrl());
      uriBuilder
          .queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
          .queryParam(OAUTH2_PARAMETER_AGENT_ID, getConfig().getAgentId())
          .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
          .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded());
      logger.info("企业微信外部浏览器，构建扫码登录链接");
    }
    logger.info("授权链接是：" + uriBuilder.build().toString());
    return uriBuilder;
  }

  public class Endpoint {
    protected AuthenticationCallback callback;
    protected RealmModel realm;
    protected EventBuilder event;

    @Context protected KeycloakSession session;

    @Context protected ClientConnection clientConnection;

    @Context protected HttpHeaders headers;

    @Context protected UriInfo uriInfo;

    public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event) {
      this.callback = callback;
      this.realm = realm;
      this.event = event;
    }

    @GET
    public Response authResponse(
        @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_STATE) String state,
        @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_CODE) String authorizationCode,
        @QueryParam(OAuth2Constants.ERROR) String error,
        @QueryParam("appid") String client_id) {
      logger.info("authResponse called. code=" + authorizationCode + ", state=" + state + ", error=" + error);

      // 以下样版代码从 AbstractOAuth2IdentityProvider 里获取的。
      if (state == null) {
        return errorIdentityProviderLogin(Messages.IDENTITY_PROVIDER_MISSING_STATE_ERROR);
      }
      try {
        AuthenticationSessionModel authSession =
            this.callback.getAndVerifyAuthenticationSession(state);
        session.getContext().setAuthenticationSession(authSession);

        if (error != null) {
          logger.error(error + " for broker login " + getConfig().getProviderId());
          if (error.equals(ACCESS_DENIED)) {
            return callback.cancelled(getConfig());
          } else if (error.equals(OAuthErrorException.LOGIN_REQUIRED)
              || error.equals(OAuthErrorException.INTERACTION_REQUIRED)) {
            return callback.error(error);
          } else {
            return callback.error(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
          }
        }

        if (authorizationCode != null) {
          BrokeredIdentityContext federatedIdentity = getFederatedIdentity(authorizationCode);

          if (federatedIdentity == null) {
            return errorIdentityProviderLogin(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
          }

          federatedIdentity.setAuthenticationSession(authSession);

          return callback.authenticated(federatedIdentity);
        }
      } catch (WebApplicationException e) {
        e.printStackTrace(System.out);
        return e.getResponse();
      } catch (Exception e) {
        logger.error("Failed to make identity provider oauth callback", e);
        e.printStackTrace(System.out);
      }
      return errorIdentityProviderLogin(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
    }

    private Response errorIdentityProviderLogin(String message) {
      event.event(EventType.IDENTITY_PROVIDER_LOGIN);
      event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
      return ErrorPage.error(session, null, Response.Status.BAD_GATEWAY, message);
    }
  }

  @Override
  public void updateBrokeredUser(
      KeycloakSession session, RealmModel realm, UserModel user, BrokeredIdentityContext context) {
    user.setSingleAttribute(PROFILE_MOBILE, context.getUserAttribute(PROFILE_MOBILE));
    user.setSingleAttribute(PROFILE_GENDER, context.getUserAttribute(PROFILE_GENDER));
    user.setSingleAttribute(PROFILE_STATUS, context.getUserAttribute(PROFILE_STATUS));
    user.setSingleAttribute(PROFILE_ENABLE, context.getUserAttribute(PROFILE_ENABLE));
    user.setSingleAttribute(PROFILE_USERID, context.getUserAttribute(PROFILE_USERID));

    user.setUsername(context.getUsername());
    user.setFirstName(context.getFirstName());
    user.setLastName(context.getLastName());
    user.setEmail(context.getEmail());
  }
}
