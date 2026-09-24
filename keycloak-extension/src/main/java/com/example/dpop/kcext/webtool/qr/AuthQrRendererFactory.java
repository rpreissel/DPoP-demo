package com.example.dpop.kcext.webtool.qr;

import com.example.dpop.kcext.KcText;
/** Web-channel counterpart of `auth-qr` - account already known via the channel (step-up/re-auth). */
public class AuthQrRendererFactory extends QrWaitRendererFactory {

    public static final String PROVIDER_ID = "auth-qr";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Mit App bestätigen");
    }

    @Override
    public KcText hint() {
        return KcText.t("QR-Code mit der App scannen oder Code manuell in der App eingeben");
    }
}
