package com.leagueakari.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册 MySQL 分页插件
 * <p>PaginationInnerInterceptor 负责把 selectPage 的查询改写为
 * 带 LIMIT 的分页 SQL，并自动执行 COUNT 统计总条数。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页拦截器 Bean：指定 MySQL 方言，使 Page 分页查询生效
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 注册 MySQL 分页插件，支持 selectPage / page 查询
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
