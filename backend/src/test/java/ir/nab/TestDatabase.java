package ir.nab;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Optional real PostgreSQL test target; otherwise each suite keeps its isolated H2 database. */
abstract class TestDatabase {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("NAB_TEST_JDBC_URL");
        if(url!=null&&!url.isBlank()){
            registry.add("spring.datasource.url",()->url);
            registry.add("spring.datasource.username",()->System.getenv().getOrDefault("NAB_TEST_DB_USER","nab_test"));
            registry.add("spring.datasource.password",()->System.getenv().getOrDefault("NAB_TEST_DB_PASSWORD","nab_test"));
        }
    }
}
