package com.example.dpop.kcext.webtool.qr;

/** Web-channel counterpart of `auth-qr` - account already known via the channel (step-up/re-auth). */
public class AuthQrRendererFactory extends QrWaitRendererFactory {

    public static final String PROVIDER_ID = "auth-qr";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "Mit App bestätigen";
    }

    @Override
    public String hint() {
        return "QR-Code mit der App scannen oder Code manuell in der App eingeben";
    }
}
