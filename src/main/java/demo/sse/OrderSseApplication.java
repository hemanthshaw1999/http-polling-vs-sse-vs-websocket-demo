package demo.sse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderSseApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--sse-load-generator")) {
            SseLoadGenerator.run(args);
            return;
        }
        SpringApplication.run(OrderSseApplication.class, args);
    }
}
