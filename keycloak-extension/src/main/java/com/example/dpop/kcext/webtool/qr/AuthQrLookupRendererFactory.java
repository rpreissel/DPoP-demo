package com.example.dpop.kcext.webtool.qr;

import com.example.dpop.kcext.KcText;
/** Web-channel counterpart of `auth-qr-lookup` - account unknown until the app side reveals it. */
public class AuthQrLookupRendererFactory extends QrWaitRendererFactory {

    public static final String PROVIDER_ID = "auth-qr-lookup";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Mit App anmelden");
    }

    @Override
    public KcText hint() {
        return KcText.t("QR-Code mit einer bereits angemeldeten App scannen oder Code manuell eingeben");
    }
}
