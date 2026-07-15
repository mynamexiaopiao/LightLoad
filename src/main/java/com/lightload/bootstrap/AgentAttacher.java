package com.lightload.bootstrap;

import com.sun.tools.attach.VirtualMachine;

/** Runs in a short-lived helper JVM so Java can attach to the Minecraft process safely. */
public final class AgentAttacher {
    private AgentAttacher() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("Expected pid, agent jar and options");
        VirtualMachine machine = VirtualMachine.attach(arguments[0]);
        try {
            machine.loadAgent(arguments[1], arguments[2]);
        } finally {
            machine.detach();
        }
        System.out.println("Lightload runtime agent attached");
    }
}
