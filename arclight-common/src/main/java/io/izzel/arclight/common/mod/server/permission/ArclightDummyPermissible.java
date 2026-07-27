package io.izzel.arclight.common.mod.server.permission;

import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.ServerOperator;

/**
 * A dummy {@link PermissibleBase} that always reports the entity as non-operator.
 *
 * <p>Used as the base for {@link io.izzel.arclight.common.mod.server.command.ArclightDummyCommandSender}
 * and other synthetic command senders that have no real operator status.</p>
 */
public class ArclightDummyPermissible extends PermissibleBase {

    /**
     * Shared singleton operator instance that always returns {@code false}
     * for {@link ServerOperator#isOp()}.
     */
    public static final ServerOperator DUMMY_OPERATOR = new ServerOperator() {
        @Override
        public boolean isOp() {
            return false;
        }

        @Override
        public void setOp(boolean b) {
            // No-op: dummy operators cannot have their status changed
        }
    };

    public ArclightDummyPermissible() {
        super(DUMMY_OPERATOR);
    }
}