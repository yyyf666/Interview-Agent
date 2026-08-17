package com.interview.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI interviewAgentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("智能自适应AI面试系统 - 业务后端API")
                .description("本地实验版：简历/JD解析、自适应面试会话、题库、知识库、评测")
                .version("0.1.0"));
    }
}
