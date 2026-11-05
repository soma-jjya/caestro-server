package app.peakpic.b2b;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class RuntimeConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        var template = new RedisTemplate<String, String>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
    @Bean LockProvider lockProvider(RedisConnectionFactory factory, RuntimeProperties properties) {
        return new RedisLockProvider(factory, properties.issuer());
    }
    @Bean @ConditionalOnProperty(name = "fill.image-store", havingValue = "s3")
    S3Client s3Client(RuntimeProperties properties) {
        return S3Client.builder().region(Region.of(properties.region())).build();
    }
    @Bean @ConditionalOnProperty(name = "fill.image-store", havingValue = "s3")
    S3Presigner s3Presigner(RuntimeProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}
