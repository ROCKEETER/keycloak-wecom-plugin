package org.keycloak.social.wechat;

import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import java.util.List;

public class WechatWorkIdentityProviderFactory
    extends AbstractIdentityProviderFactory<WechatWorkIdentityProvider>
    implements SocialIdentityProviderFactory<WechatWorkIdentityProvider> {

  public static final String PROVIDER_ID = "wechat-work";

  @Override
  public String getName() {
    return "WechatWork";
  }

  @Override
  public WechatWorkIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
    return new WechatWorkIdentityProvider(session, new WechatWorkProviderConfig(model));
  }

  @Override
  public IdentityProviderModel createConfig() {
    return new WechatWorkProviderConfig();
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
      return ProviderConfigurationBuilder.create()
              .property()
              .name("agentId")
              .label("Agent ID")
              .helpText("企业微信应用的 AgentId")
              .type(ProviderConfigProperty.STRING_TYPE)
              .add()
              .build();
  }
}
