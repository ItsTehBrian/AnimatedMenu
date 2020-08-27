package me.megamichiel.animatedmenu.menu.config;

import me.megamichiel.animatedmenu.AnimatedMenuPlugin;
import me.megamichiel.animatedmenu.command.CommandExecutor;
import me.megamichiel.animatedmenu.util.Delay;
import me.megamichiel.animatedmenu.util.Flag;
import me.megamichiel.animatedmenu.util.PluginCurrency;
import me.megamichiel.animationlib.config.ConfigSection;
import me.megamichiel.animationlib.placeholder.StringBundle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

public class ClickHandler {

    public static final String PERMISSION_MESSAGE = "&cYou are not permitted to do that!";

    private static final Purchase<?>[] PURCHASES = {
            Purchase.ofCurrency("price", "money", PluginCurrency.VAULT, Double::valueOf, 0D),
            //TODO Purchase.ofCurrency("points", "points", PluginCurrency.PLAYER_POINTS, Integer::valueOf, 0),
            //TODO Purchase.ofCurrency("gems", "gems", PluginCurrency.GEMS, Integer::valueOf, 0),
            Purchase.ofCurrency("tokens", "tokens", PluginCurrency.TOKENS, Integer::valueOf, 0),
            //TODO Purchase.ofCurrency("coins", "coins", PluginCurrency.COINS, Double::valueOf, 0D),
            Purchase.ofCurrency("pointsapi", "points", PluginCurrency.POINTS_API, Integer::valueOf, 0),

            new ClickHandler.Purchase<Number>("exp", "&cYou don't have enough exp for that!") {
                @Override
                protected Number parse(AnimatedMenuPlugin plugin, String value) {
                    try {
                        if (value.charAt(0) == 'L' || value.charAt(0) == 'l') {
                            int i = Integer.parseInt(value.substring(1));
                            return i > 0 ? i : null;
                        }
                        float f = Float.parseFloat(value);
                        return f > 0f ? f : null;
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }

                @Override
                protected boolean test(AnimatedMenuPlugin plugin, Player player, Number value) {
                    return value instanceof Float
                            ? player.getExp() >= value.floatValue()
                            : player.getLevel() >= value.intValue();
                }

                @Override
                protected void take(AnimatedMenuPlugin plugin, Player player, Number value) {
                    if (value instanceof Float) {
                        player.setExp(player.getExp() - value.floatValue());
                    } else {
                        player.setLevel(player.getLevel() - value.intValue());
                    }
                }
            }
    };

    private final List<Entry> entries = new ArrayList<>();

    public ClickHandler(ConfigMenuProvider loader, String menu, String item, ConfigSection section) {
        Map<String, Object> values = null;
        Object o = section.get("click-handlers");
        if (o instanceof ConfigSection) {
            values = ((ConfigSection) o).values();
        } else if (o instanceof Collection) {
            int i = 0;
            values = new HashMap<>();
            for (Object obj : ((Collection) o)) {
                values.put(Integer.toString(++i), obj);
            }
        } else if ((o = section.get("commands")) != null) {
            if (o instanceof ConfigSection) {
                values = ((ConfigSection) o).values();
            } else if (o instanceof List) {
                (values = new HashMap<>()).put("(self)", section);
            }
        }
        if (values != null) {
            values.forEach((key, value) -> {
                if (value instanceof ConfigSection) {
                    String path = key + '_' + item + '_' + menu;
                    Entry entry = loader.parseClickHandler(path, (ConfigSection) value);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            });
        }
    }

    public void click(Player player, ClickType type) {
        for (Entry entry : entries) entry.click(player, type);
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    public static class Entry {

        private final ClickPredicate click;

        private final StringBundle permission, permissionMessage, bypassPermission;
        private final PurchaseData<?>[] purchases;

        private final CommandExecutor clickExecutor;
        private final CloseAction closeAction;

        private final Delay delay;

        public Entry(String path, AnimatedMenuPlugin plugin, ConfigMenuProvider provider, ConfigSection section, Purchase<?>... purchases) {
            click = new ClickPredicate(section);
            (clickExecutor = new CommandExecutor(plugin)).load(plugin, section, "commands");

            permission = StringBundle.parse(plugin, section.getString("permission"));
            permissionMessage = StringBundle.parse(plugin, section.getString("permission-message", PERMISSION_MESSAGE)).colorAmpersands();
            bypassPermission = StringBundle.parse(plugin, section.getString("bypass-permission"));

            Purchase<?>[] allPurchases = Arrays.copyOf(PURCHASES, PURCHASES.length + purchases.length);
            System.arraycopy(purchases, 0, allPurchases, PURCHASES.length, purchases.length);

            this.purchases = Stream.of(allPurchases).map(p -> p.tryParse(plugin, section))
                    .filter(Objects::nonNull).toArray(PurchaseData[]::new);

            CloseAction closeAction = CloseAction.NEVER;
            String close = section.getString("close");
            if (close != null) {
                try {
                    closeAction = CloseAction.valueOf(close.toUpperCase(Locale.ENGLISH).replace('-', '_'));
                } catch (IllegalArgumentException ex) {
                    if (Flag.parseBoolean(close)) {
                        closeAction = CloseAction.ON_SUCCESS;
                    }
                }
            }
            this.closeAction = closeAction;

            long clickDelay = provider.parseTime(section, "click-delay", 0) * 50L;
            delay = clickDelay > 0 ? plugin.addPlayerDelay("item_" + path, section.getString("delay-message"), clickDelay) : null;
        }

        void click(Player player, ClickType type) {
            if (click.test(type)) {
                boolean click = canClick(player);
                if (click) {
                    clickExecutor.accept(player);
                }
                if (click ? closeAction.onSuccess : closeAction.onFailure) {
                    player.closeInventory();
                }
            }
        }

        protected boolean canClick(Player player) {
            if (permission != null && !player.hasPermission(permission.toString(player))) {
                player.sendMessage(permissionMessage.toString(player));
                return false;
            }
            if (delay == null || delay.test(player)) {
                if (bypassPermission == null || !player.hasPermission(bypassPermission.toString(player))) {
                    for (PurchaseData<?> purchase : purchases) {
                        if (!purchase.test(player)) {
                            return false;
                        }
                    }
                    for (PurchaseData<?> purchase : purchases) {
                        purchase.perform(player);
                    }
                }
                return true;
            }

            return false;
        }
    }

    private static class ClickPredicate {

        private final boolean right, left, middle;
        private final Flag shift;

        private ClickPredicate(ConfigSection section) {
            String click = section.getString("click-type", "both").toLowerCase(Locale.ENGLISH);
            switch (click) {
                case "all": right = left = middle = true; break;
                case "both": middle = !(right = left = true); break;
                default:
                    boolean right  = false,
                            left   = false,
                            middle = false;
                    for (String s : click.split(",")) {
                        switch (s.trim()) {
                            case "right":   right = true; break;
                            case "left":     left = true; break;
                            case "middle": middle = true; break;
                        }
                    }
                    this.right  = right;
                    this.left   = left;
                    this.middle = middle;
                    break;
            }
            if (!right && !left && !middle) {
                throw new IllegalArgumentException("No click types enabled!");
            }
            this.shift = Flag.parseFlag(section.getString("shift-click"), Flag.BOTH);
        }

        boolean test(ClickType type) {
            return shift.matches(type.isShiftClick()) && (type.isRightClick() && right
                    || type.isLeftClick() && left || type == ClickType.MIDDLE && middle);
        }
    }

    public static abstract class Purchase<T> {

        static <N extends Number & Comparable<N>> Purchase<N> ofCurrency(String cfg, String name, PluginCurrency<N> currency, Function<String, N> parser, N zero) {
            return new Purchase<N>(cfg, "&cYou don't have enough " + name + " for that!") {
                @Override
                protected N parse(AnimatedMenuPlugin plugin, String value) {
                    N number;
                    try {
                        return currency.isAvailable() && (number = parser.apply(value)).compareTo(zero) > 0 ? number : null;
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }

                @Override
                protected boolean test(AnimatedMenuPlugin plugin, Player player, N value) {
                    return currency.has(player, value);
                }

                @Override
                protected void take(AnimatedMenuPlugin plugin, Player player, N value) {
                    currency.take(player, value);
                }
            };
        }

        final String path, defaultMessage;

        protected Purchase(String path, String defaultMessage) {
            this.path = path;
            this.defaultMessage = defaultMessage;
        }

        PurchaseData<T> tryParse(AnimatedMenuPlugin plugin, ConfigSection config) {
            String str = config.getString(path);
            if (str != null) {
                T value = parse(plugin, str);
                if (value != null) {
                    return new PurchaseData<>(plugin, this, value, StringBundle.parse(plugin,
                            config.getString(path + "-message", defaultMessage)).colorAmpersands());
                }
            }
            return null;
        }

        protected abstract T parse(AnimatedMenuPlugin plugin, String value);
        protected abstract boolean test(AnimatedMenuPlugin plugin, Player player, T value);
        protected abstract void take(AnimatedMenuPlugin plugin, Player player, T value);
    }

    static class PurchaseData<T> {

        private final AnimatedMenuPlugin plugin;
        private final Purchase<T> purchase;
        private final T value;
        private final StringBundle message;

        PurchaseData(AnimatedMenuPlugin plugin, Purchase<T> purchase, T value, StringBundle message) {
            this.plugin = plugin;
            this.purchase = purchase;
            this.value = value;
            this.message = message;
        }

        boolean test(Player player) {
            if (purchase.test(plugin, player, value)) {
                return true;
            }
            player.sendMessage(message.toString(player));
            return false;
        }

        void perform(Player player) {
            purchase.take(plugin, player, value);
        }
    }

    private enum CloseAction {

        ALWAYS(true, true),
        ON_SUCCESS(true, false),
        ON_FAILURE(false, true),
        NEVER(false, false);

        private final boolean onSuccess, onFailure;

        CloseAction(boolean onSuccess, boolean onFailure) {
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }
    }
}
