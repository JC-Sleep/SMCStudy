
package sys.smc.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付系统主启动类
 *
 * @author System
 * @date 2026-03-23
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("sys.smc.payment.mapper")
public class PaymentSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentSystemApplication.class, args);
        System.out.println("========================================");
        System.out.println("支付系统启动成功！");
        System.out.println("渣打银行支付网关对接系统");
        System.out.println("========================================");
    }
}

