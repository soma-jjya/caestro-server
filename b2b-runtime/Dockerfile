FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# RDS uses its own CA bundle. Keep VERIFY_IDENTITY and import the official roots.
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /tmp/rds-bundle.pem
RUN mkdir /tmp/rds-certs && cp "$JAVA_HOME/lib/security/cacerts" /workspace/rds-cacerts && \
    awk '/-----BEGIN CERTIFICATE-----/{n++; active=1} active {print > ("/tmp/rds-certs/" n ".pem")} /-----END CERTIFICATE-----/{active=0}' /tmp/rds-bundle.pem && \
    for cert in /tmp/rds-certs/*.pem; do keytool -importcert -noprompt -alias "rds-$(basename "$cert")" -file "$cert" -keystore /workspace/rds-cacerts -storepass changeit; done
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY engine engine
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
COPY --from=build /workspace/rds-cacerts /app/rds-cacerts
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-Djavax.net.ssl.trustStore=/app/rds-cacerts", "-Djavax.net.ssl.trustStorePassword=changeit", "-jar", "/app/app.jar"]
