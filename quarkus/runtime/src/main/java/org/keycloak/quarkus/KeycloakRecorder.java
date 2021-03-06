/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
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
 */

package org.keycloak.quarkus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.smallrye.config.ConfigValue;
import org.keycloak.QuarkusKeycloakSessionFactory;
import org.keycloak.authentication.authenticators.directgrant.ValidateOTP;
import org.keycloak.cli.ShowConfigCommand;
import org.keycloak.common.Profile;
import org.keycloak.configuration.MicroProfileConfigProvider;
import org.keycloak.configuration.PropertyMappers;
import org.keycloak.connections.liquibase.FastServiceLocator;
import org.keycloak.connections.liquibase.KeycloakLogger;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigProviderResolver;
import liquibase.logging.LogFactory;
import liquibase.servicelocator.ServiceLocator;

@Recorder
public class KeycloakRecorder {

    private static final SmallRyeConfig CONFIG;
    
    static {
        CONFIG = (SmallRyeConfig) SmallRyeConfigProviderResolver.instance().getConfig();
    }

    private static Map<String, String> BUILD_TIME_PROPERTIES = Collections.emptyMap();
    
    public static String getBuiltTimeProperty(String name) {
        return BUILD_TIME_PROPERTIES.get(name);
    }

    public static SmallRyeConfig getConfig() {
        return CONFIG;
    }

    public void configureLiquibase(Map<String, List<String>> services) {
        LogFactory.setInstance(new LogFactory() {
            final KeycloakLogger logger = new KeycloakLogger();

            @Override
            public liquibase.logging.Logger getLog(String name) {
                return logger;
            }

            @Override
            public liquibase.logging.Logger getLog() {
                return logger;
            }
        });
        
        // we set this property to avoid Liquibase to lookup resources from the classpath and access JAR files
        // we already index the packages we want so Liquibase will still be able to load these services
        // for uber-jar, this is not a problem because everything is inside the JAR, but once we move to fast-jar we'll have performance penalties
        // it seems that v4 of liquibase provides a more smart way of initialization the ServiceLocator that may allow us to remove this
        System.setProperty("liquibase.scan.packages", "org.liquibase.core");
        
        ServiceLocator.setInstance(new FastServiceLocator(services));
    }

    public void configSessionFactory(
            Map<Spi, Map<Class<? extends Provider>, Map<String, Class<? extends ProviderFactory>>>> factories,
            Map<Class<? extends Provider>, String> defaultProviders,
            Boolean reaugmented) {
        Profile.setInstance(createProfile());
        QuarkusKeycloakSessionFactory.setInstance(new QuarkusKeycloakSessionFactory(factories, defaultProviders, reaugmented));
    }

    public void setBuildTimeProperties(Map<String, String> buildTimeProperties, Boolean rebuild) {
        BUILD_TIME_PROPERTIES = buildTimeProperties;

        for (String propertyName : getConfig().getPropertyNames()) {
            if (!propertyName.startsWith(MicroProfileConfigProvider.NS_KEYCLOAK_PREFIX)) {
                continue;
            }

            String buildValue = BUILD_TIME_PROPERTIES.get(propertyName);
            ConfigValue value = getConfig().getConfigValue(propertyName);

            if (PropertyMappers.isBuildTimeProperty(propertyName)) {
                if (!rebuild && buildValue != null && value.getValue() != null && !buildValue.equalsIgnoreCase(value.getValue())) {
                    System.err.println("The value [" + value.getValue() + "] for property [" + propertyName + "] differs from the value [" + buildValue + "] set into the server image. Please, run the 'config' command to configure the server with the new value.");
                    System.exit(1);
                }
                if (!value.getConfigSourceName().contains("keycloak.properties")) {
                    System.err.println("The property [" + propertyName + "] can only be set when configuring the server. Please, run the 'config' command.");
                    System.exit(1);
                }
            }
            
            if (buildValue != null && isRuntimeValue(value) && !buildValue.equalsIgnoreCase(value.getValue())) {
                System.err.println("The value [" + value.getValue()  + "] of property [" + propertyName + "] differs from the value [" + buildValue + "] set into the server image");
                System.exit(1);
            }
        }
    }

    private boolean isRuntimeValue(ConfigValue value) {
        return value.getValue() != null && !PropertyMappers.isBuildTimeProperty(value.getName());
    }

    /**
     * This method should be executed during static init so that the configuration is printed (if demanded) based on the properties
     * set from the previous reaugmentation
     */
    public void showConfig() {
        ShowConfigCommand.run(BUILD_TIME_PROPERTIES);
    }

    public static Profile createProfile() {
        return new Profile(new Profile.PropertyResolver() {
            @Override 
            public String resolve(String feature) {
                if (feature.startsWith("keycloak.profile.feature")) {
                    feature = feature.replaceAll("keycloak\\.profile\\.feature", "kc\\.features");    
                } else {
                    feature = "kc.features";
                }

                String value = getBuiltTimeProperty(feature);

                if (value == null) {
                    value = getBuiltTimeProperty(feature.replaceAll("\\.features\\.", "\\.features-"));
                }
                
                if (value != null) {
                    return value;
                }

                return KeycloakRecorder.getConfig().getRawValue(feature);
            }
        });
    }
}
