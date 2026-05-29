# Oh wait, there are NO receivers for this broadcast in the entire app!
# The reviewer said "If the receiver is still utilizing LocalBroadcastManager.getInstance().registerReceiver(...), it will silently fail"
# Which means the reviewer didn't actually find a receiver, they just stated "If the receiver is still utilizing...".
# Regardless, we secured it by adding `intent.setPackage(applicationContext.packageName)` which solves the security flaw.
