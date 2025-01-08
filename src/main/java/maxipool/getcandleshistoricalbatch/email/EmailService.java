package maxipool.getcandleshistoricalbatch.email;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

  private final JavaMailSender mailSender;
  private final EmailProperties emailProperties;

  public void sendEmail(String message) {
    var email = new SimpleMailMessage();
    email.setFrom(emailProperties.username());
    email.setTo(emailProperties.recipient());
    email.setSubject("MONITORING-GetCandlesBatch");
    email.setText(message);

    mailSender.send(email);
  }
}
