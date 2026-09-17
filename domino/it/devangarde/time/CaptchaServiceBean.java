package it.devangarde.time;

import it.devangarde.ServiceBean;
import it.devangarde.captcha.CaptchaService;

/**
 * GET .../api.xsp/captcha
 *
 * Nessuno slug: il captcha non dipende dal professionista. Va richiamato solo
 * nella fase di riepilogo, subito prima della conferma (il token ha TTL breve
 * e non deve scadere prima che l'utente lo compili).
 */
public class CaptchaServiceBean extends ServiceBean {

    public void get() throws Exception {
        CaptchaService svc = new CaptchaService("TODO"); // TODO
        CaptchaService.Captcha c = svc.generate();

        this.body.put("image", c.imageBase64);
        this.body.put("token", c.token);
        this.body.put("exp", c.exp.toInstant().toString());
    }
}
