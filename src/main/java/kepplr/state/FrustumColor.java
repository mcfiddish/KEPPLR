package kepplr.state;

import java.util.Locale;
import java.util.regex.Pattern;

/** RGB color for an instrument frustum overlay, with 8-bit channel values. */
public record FrustumColor(int red, int green, int blue) {

    private static final Pattern HEX_COLOR = Pattern.compile("#?[0-9a-fA-F]{6}");

    public FrustumColor {
        validate("red", red);
        validate("green", green);
        validate("blue", blue);
    }

    public static FrustumColor rgb(int red, int green, int blue) {
        return new FrustumColor(red, green, blue);
    }

    public static FrustumColor hex(String hexColor) {
        if (hexColor == null) {
            throw new IllegalArgumentException("hexColor must not be null");
        }
        String trimmed = hexColor.trim();
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("hexColor must be RRGGBB or #RRGGBB: " + hexColor);
        }
        String digits = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        int rgb = Integer.parseInt(digits, 16);
        return new FrustumColor((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    public float redFloat() {
        return red / 255f;
    }

    public float greenFloat() {
        return green / 255f;
    }

    public float blueFloat() {
        return blue / 255f;
    }

    public String toHexString() {
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
    }

    private static void validate(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be in [0, 255]: " + value);
        }
    }
}
