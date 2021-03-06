package in.org.projecteka.hiu.dataflow;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "hiu.dataflowservice")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@AllArgsConstructor
public class DataFlowServiceProperties {
    private int maxPageSize;
    private int defaultPageSize;
    private String localStoragePath;
    private int dataFlowRequestWaitTime;
    private int dataPartWaitTime;
}
