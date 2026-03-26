package sys.smc.coupon;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优惠券系统启动类
 * 
 * @author CouponSys
 */
@SpringBootApplication
@MapperScan("sys.smc.coupon.mapper")
@EnableAsync
@EnableScheduling
@EnableJms
public class CouponSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouponSystemApplication.class, args);
        System.out.println("========================================");
        System.out.println("   Coupon System Started Successfully   ");
        System.out.println("========================================");
    }
}

