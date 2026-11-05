package com.mcworldexplorer.voxel.resource;

import java.util.Objects;

/** Immutable texture alias or sprite reference with an explicit render-pass hint. */
public record TextureBinding(String reference,boolean forceTranslucent) {
    public TextureBinding {
        Objects.requireNonNull(reference);
        if(reference.isBlank()||reference.length()>512||reference.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid texture reference");
    }
}
