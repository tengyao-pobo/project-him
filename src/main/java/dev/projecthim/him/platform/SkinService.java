package dev.projecthim.him.platform;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.UUID;
import org.bukkit.entity.Mannequin;

/**
 * Builds the profile used for HIM's body. The texture must be signed by Mojang because vanilla
 * clients refuse unsigned player skins; the default configuration ships a signed classic
 * white-eyed texture.
 */
public final class SkinService {

    /** Fixed profile id, so clients can cache the downloaded skin across appearances. */
    private static final UUID PROFILE_ID = UUID.fromString("5c4f2d84-61a8-4b4e-9d1c-7a3e0b9e1d07");

    private final ResolvableProfile profile;
    private final boolean customSkin;

    public SkinService(String textureValue, String textureSignature) {
        if (textureValue == null || textureValue.isBlank()) {
            this.profile = Mannequin.defaultProfile();
            this.customSkin = false;
        } else {
            ProfileProperty textures = textureSignature == null || textureSignature.isBlank()
                    ? new ProfileProperty("textures", textureValue)
                    : new ProfileProperty("textures", textureValue, textureSignature);
            this.profile = ResolvableProfile.resolvableProfile()
                    .uuid(PROFILE_ID)
                    .name("")
                    .addProperty(textures)
                    .build();
            this.customSkin = true;
        }
    }

    public ResolvableProfile profile() {
        return profile;
    }

    public boolean customSkin() {
        return customSkin;
    }
}
