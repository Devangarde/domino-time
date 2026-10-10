package it.devangarde.time;

import it.devangarde.ServiceBean;
import it.devangarde.captcha.CaptchaService;

/**
 * GET .../api.xsp/captcha
 *
 * No slug: the captcha does not depend on the user. It should only
 * be requested in the summary step, right before confirmation (the token has
 * a short TTL and must not expire before the user fills it in).
 */
public class CaptchaServiceBean extends ServiceBean {

	public void get() throws Exception {
        CaptchaService svc = new CaptchaService(getSalt());
        CaptchaService.Captcha c = svc.generate();

        this.body.put("image", c.imageBase64);
        this.body.put("token", c.token);
        this.body.put("exp", c.exp.toInstant().toString());
    }
}
