package com.amazon.vidyacloneservice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazon.aws.cloudwatch.reporter.CloudWatchReporterFactory;
import com.amazon.coral.guice.GuiceActivityHandler;
import com.amazon.coral.guice.health.GuiceActivityHealthCheck;
import com.amazon.coral.metrics.MetricsFactory;
import com.amazon.coral.metrics.helper.MetricsHelper;
import com.amazon.coral.metrics.helper.QuerylogHelper;

import com.amazon.coral.service.ChainComponent;
import com.amazon.coral.service.EnvironmentChecker;
import com.amazon.coral.service.HttpHandler;
import com.amazon.coral.service.HttpRpcHandler;
import com.amazon.coral.service.Log4jAwareRequestIdHandler;
import com.amazon.coral.service.Orchestrator;
import com.amazon.coral.service.PingHandler;
import com.amazon.coral.service.ServiceHandler;
import com.amazon.coral.validate.ValidationHandler;
import com.amazon.coral.bobcat.Bobcat3EndpointConfig;
import com.amazon.coral.bobcat.BobcatServer;
import com.amazon.coral.service.helper.ChainHelper;
import com.amazon.coral.service.helper.OrchestratorHelper;
import com.amazon.coral.service.http.ContentHandler;
import com.amazon.coral.service.http.CrossOriginHandler;
import com.amazon.vidyacloneservice.health.DeepHealthCheck;
import com.amazon.vidyacloneservice.health.ShallowHealthCheck;
import com.amazon.guice.brazil.AppConfigBinder;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import static com.amazon.coral.bobcat.Bobcat3EndpointConfig.uri;

public class CoralModule extends AbstractModule {
    // ELB public docs recommend setting the idle timeout of the server to be longer than
    // that of the load balancer. The default idle timeout of an ALB is 60s and NLB is 350s.
    // ALB: https://docs.aws.amazon.com/elasticloadbalancing/latest/application/introduction.html
    // NLB: https://docs.aws.amazon.com/elasticloadbalancing/latest/network/introduction.html
    private static final int IDLE_TIMEOUT_SECONDS = 65;
    private static final Logger log = LogManager.getLogger(CoralModule.class);

    private final String root;
    private final String realm;
    private final String domain;

    CoralModule(String root, String domain, String realm) {
        this.root = root;
        this.realm = realm;
        this.domain = domain;
    }

    @Override
    protected void configure() {
        try {
            bind(EnvironmentChecker.class)
                .toConstructor(EnvironmentChecker.class.getConstructor(MetricsFactory.class)).asEagerSingleton();
        } catch (NoSuchMethodException | SecurityException e) {
            throw new RuntimeException("Failed to bind environment checker.", e);
        }
    }

    @Provides
    @Singleton
    AmazonCloudWatchAsyncClient getAmazonCloudWatch() {
        final AmazonCloudWatchAsyncClient cloudwatch = new AmazonCloudWatchAsyncClient();
        final Region region = getRegion();
        cloudwatch.setRegion(region);
        return cloudwatch;
    }

    Region getRegion() {
        
        final Region region;
        Region ec2Region = Regions.getCurrentRegion();
        if (ec2Region == null) {
            String envRegion = System.getenv("AWS_REGION");  // In ECS containers, AWS_REGION is set
            if (envRegion != null) {
                ec2Region = Region.getRegion(Regions.fromName(envRegion));
            }
        }
        if (ec2Region == null) {
            if (!"test".equals(domain)) {
                throw new RuntimeException("Couldn't identify region for CloudWatch logging from EC2");
            } else {
                log.warn("Could not identify the region we are running in. "
                    + "Domain is test; using us-west-2");
                region = Region.getRegion(Regions.US_WEST_2);
            }
        } else {
            region = ec2Region;
        }
        return region;
        
    }

    @Provides
    @Singleton
    MetricsFactory getMetricsFactory() {
        
        MetricsHelper metricsFactory = new MetricsHelper();
        CloudWatchReporterFactory cloudWatchReporterFactory = new CloudWatchReporterFactory();
        AmazonCloudWatch cloudwatch = AmazonCloudWatchClientBuilder.standard().withRegion(getRegion().getName()).build();
        cloudWatchReporterFactory.withCloudWatchClient(cloudwatch);
        // since the metrics are not sent within Lambda, we should disable autoFlush to get better metric aggregation,
        // which improves performance and reduces cost from number of API calls.
        cloudWatchReporterFactory.withAutoFlush(false);
        cloudWatchReporterFactory.withNamespace("vidyacloneservice");

        QuerylogHelper queryLogHelper = new QuerylogHelper();
        queryLogHelper.setFilename(root + "/var/output/logs/service_log");
        metricsFactory.setReporters(ImmutableList.of(
                queryLogHelper,
                cloudWatchReporterFactory));
        metricsFactory.setProgram("vidyacloneservice");
        metricsFactory.setMarketplace(String.format("vidyacloneservice:%s:%s", domain, realm));
        return metricsFactory;
        
    }

    @Provides
    @Singleton
    BobcatServer getBobcatServer(Orchestrator coral, MetricsFactory metricsFactory) throws Throwable {
        Bobcat3EndpointConfig endpointConfig = new Bobcat3EndpointConfig();
        endpointConfig.setMetricsFactory(metricsFactory);
        endpointConfig.setOrchestrator(coral);
        endpointConfig.setNumThreads(2 * Runtime.getRuntime().availableProcessors());
        endpointConfig.setEndpoints(Arrays.asList(uri("http://0.0.0.0:8080")));
        endpointConfig.setOverrideRequestId(true);
        endpointConfig.setIdleTimeout(Duration.ofSeconds(IDLE_TIMEOUT_SECONDS));
        return new BobcatServer(endpointConfig);
    }

    @Provides
    @Singleton
    Orchestrator getOrchestrator(MetricsFactory metricsFactory, Injector injector)
        throws Exception {

        List<ChainComponent> handlerChain = new ArrayList<>();
        handlerChain.add(new Log4jAwareRequestIdHandler());
        handlerChain.add(new HttpHandler());
        handlerChain.add(new CrossOriginHandler());
        handlerChain.add(new PingHandler(new ShallowHealthCheck()));
        PingHandler deepPingHandler = new PingHandler(new DeepHealthCheck(new GuiceActivityHealthCheck(injector)));
        deepPingHandler.setLocalhostOnly(true);
        deepPingHandler.setURIs(Collections.singletonList("/deep_ping"));
        handlerChain.add(deepPingHandler);

        ContentHandler contentHandler = new ContentHandler.Builder()
            .withDirectories(root + "/static-content")
            .build();
        handlerChain.add(contentHandler);
        handlerChain.add(new ServiceHandler("VidyacloneService"));

        handlerChain.add(new HttpRpcHandler());

        handlerChain.add(new ValidationHandler());
        handlerChain.add(new GuiceActivityHandler(injector).build());

        ChainHelper chainHelper = new ChainHelper();
        chainHelper.setHandlers(handlerChain);
        Orchestrator coral = new OrchestratorHelper(chainHelper, 30000);
        return coral;
    }
}
