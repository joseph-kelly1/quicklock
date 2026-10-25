package io.github.josephkelly.quicklock;

import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

public final class ProtectionGameTests {
    private static BlockPos at(GameTestHelper h, int x, int y, int z) { return h.absolutePos(new BlockPos(x,y,z)); }
    private static void check(GameTestHelper h, boolean condition, String message) { h.assertTrue(condition, message); }
    private static LockState.Lock protect(GameTestHelper h, BlockPos pos, UUID owner) {
        var p = Protection.active;
        String key = Protection.key(h.getLevel(), pos);
        if (!p.state.placements.containsKey(key)) p.state.placed(key, owner, Protection.blockId(h.getLevel().getBlockState(pos)));
        var lock = p.state.protect(p.keys(h.getLevel(), pos), owner, false, h.getLevel().getBlockState(pos).getBlock() instanceof ShulkerBoxBlock);
        p.syncWitnesses(h.getLevel(),pos);
        p.save();
        return lock;
    }
    private static void cleanup(UUID owner) {
        var p = Protection.active;
        for (var lock : new ArrayList<>(p.state.locks.values())) if (lock.owner.equals(owner)) p.state.unlock(lock);
        p.save();
    }
    private static boolean place(GameTestHelper h, Player player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        return ((BlockItem) stack.getItem()).place(context).consumesAction();
    }

    private record Client(ServerPlayer player, List<Packet<?>> packets) implements AutoCloseable {
        @Override public void close() {
            var p = Protection.active;
            AccessLabels.clear(player);
            p.server.getPlayerList().getPlayers().remove(player);
            p.state.viewers.remove(player.getUUID());
            p.forget(player.getUUID());
        }
    }
    private static Client client(GameTestHelper h) {
        ServerPlayer player = (ServerPlayer) h.makeMockServerPlayer(GameType.SURVIVAL);
        List<Packet<?>> packets = new ArrayList<>();
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { packets.add(packet); }
        };
        new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player, CommonListenerCookie.createInitial(player.getGameProfile(),false));
        h.getLevel().getServer().getPlayerList().getPlayers().add(player);
        Protection.active.state.names.put(player.getUUID(),player.getGameProfile().name());
        return new Client(player,packets);
    }
    private static void aim(ServerPlayer player, BlockPos pos) {
        player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+3);
        player.lookAt(EntityAnchorArgument.Anchor.EYES,Vec3.atCenterOf(pos));
    }

    @GameTest
    public void commandsRespectTargetingAndIndependentGrants(GameTestHelper h) {
        try (Client c = client(h)) {
            var p = Protection.active;
            ServerPlayer player = c.player;
            BlockPos pos = at(h,1,2,1);
            UUID friend = UUID.randomUUID();
            p.state.names.put(friend,"QuickFriend");
            try {
                check(h,place(h,player,pos,new ItemStack(Blocks.BARREL)),"Player placement failed");
                aim(player,pos);
                var commands = p.server.getCommands();
                commands.performPrefixedCommand(player.createCommandSourceStack(),"lock");
                var lock = p.lock(h.getLevel(),pos);
                check(h,lock!=null && lock.owner.equals(player.getUUID()),"Lock command failed");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"trust QuickFriend");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"trust-all QuickFriend");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"untrust QuickFriend");
                check(h,!p.state.localTrust(lock,friend) && p.state.globalTrust(lock,friend),"Untrust removed wrong grant");
                check(h,c.packets.stream().anyMatch(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.content().getString().contains("Owner-wide trust still allows access.")),"Remaining grant feedback missing");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"lockinfo");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"lockview");
                check(h,p.state.viewers.contains(player.getUUID()),"View toggle not recorded");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"unlock");
                check(h,p.lock(h.getLevel(),pos)==null,"Unlock command failed");
                commands.performPrefixedCommand(player.createCommandSourceStack(),"lockhelp");
                h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                commands.performPrefixedCommand(player.createCommandSourceStack(),"lock");
                check(h,c.packets.stream().anyMatch(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.content().getString().equals("This is not lockable")),"Exact ineligible-block feedback missing");
            } finally { cleanup(player.getUUID()); p.state.globalTrust.remove(player.getUUID()); p.state.names.remove(friend); }
        }
        h.succeed();
    }

    @GameTest
    public void labelsSendPrivateVanillaPacketsAndCleanUp(GameTestHelper h) {
        try (Client owner=client(h); Client stranger=client(h)) {
            var p = Protection.active;
            BlockPos pos = at(h,1,2,1);
            try {
                h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
                var lock=protect(h,pos,owner.player.getUUID());
                aim(owner.player,pos); aim(stranger.player,pos);
                p.state.viewers.add(owner.player.getUUID()); p.state.viewers.add(stranger.player.getUUID());
                AccessLabels.tick(p);
                check(h,owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count()==1,"Owner label spawn missing");
                check(h,stranger.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count()==1,"Stranger label spawn missing");
                check(h,labelText(owner,"Unlocked"),"Owner label text incorrect");
                check(h,labelText(stranger,"Locked"),"Stranger label text incorrect");
                int ownerId=owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).map(x -> ((ClientboundAddEntityPacket)x).getId()).findFirst().orElseThrow();
                check(h,h.getLevel().getEntity(ownerId)==null,"Private display entered shared world");
                AccessLabels.tick(p);
                check(h,owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count()==1,"Unchanged label resent");
                lock.trusted.add(stranger.player.getUUID());
                AccessLabels.tick(p);
                check(h,labelText(stranger,"Unlocked"),"Trust change did not update private label");
                owner.player.setYRot(180); owner.player.setXRot(0);
                AccessLabels.tick(p);
                check(h,owner.packets.stream().anyMatch(x -> x instanceof ClientboundRemoveEntitiesPacket),"Look-away failed cleanup");
                aim(owner.player,pos);
                AccessLabels.tick(p);
                long spawns=owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count();
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(owner.player,owner.player,false);
                AccessLabels.tick(p);
                check(h,owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count()==spawns+1,"Respawn retained stale label cache");
                spawns++;
                net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.invoker().afterChangeLevel(owner.player,h.getLevel(),h.getLevel());
                AccessLabels.tick(p);
                check(h,owner.packets.stream().filter(x -> x instanceof ClientboundAddEntityPacket).count()==spawns+1,"Level change retained stale label cache");
            } finally { cleanup(owner.player.getUUID()); }
        }
        h.succeed();
    }

    private static boolean labelText(Client client,String text) {
        return client.packets.stream().filter(x -> x instanceof ClientboundSetEntityDataPacket)
                .map(x -> (ClientboundSetEntityDataPacket)x).flatMap(x -> x.packedItems().stream())
                .anyMatch(v -> v.value() instanceof net.minecraft.network.chat.Component component && component.getString().equals(text));
    }

    @GameTest
    public void shulkerBreakPickupReplacementConservesContents(GameTestHelper h) {
        try (Client owner=client(h); Client stranger=client(h)) {
            var p=Protection.active;
            BlockPos pos=at(h,1,2,1), replacement=pos.east();
            try {
                check(h,place(h,owner.player,pos,new ItemStack(Blocks.SHULKER_BOX)),"Shulker placement failed");
                var lock=protect(h,pos,owner.player.getUUID());
                UUID friend=UUID.randomUUID(); lock.trusted.add(friend);
                ((Container)h.getLevel().getBlockEntity(pos)).setItem(0,new ItemStack(Items.DIAMOND,7));
                stranger.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_PICKAXE));
                check(h,!stranger.player.gameMode.destroyBlock(pos),"Foreign player broke shulker");
                owner.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_PICKAXE));
                check(h,owner.player.gameMode.destroyBlock(pos),"Owner could not break shulker");
                var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(.9),e -> Protection.hasToken(e.getItem()));
                check(h,drops.size()==1,"Expected exactly one portable shulker drop");
                ItemEntity drop=drops.getFirst();
                check(h,lock.id.equals(Protection.token(drop.getItem())),"Drop lost lock identity");
                drop.setNoPickUpDelay();
                drop.playerTouch(stranger.player);
                check(h,!drop.isRemoved(),"Stranger picked up locked shulker");
                ItemStack carried=drop.getItem().copy();
                drop.playerTouch(owner.player);
                check(h,drop.isRemoved(),"Owner could not pick up shulker");
                // Use the actual picked-up stack, removing it from inventory before placement.
                int slot=-1;
                for(int i=0;i<owner.player.getInventory().getContainerSize();i++) if(Protection.hasToken(owner.player.getInventory().getItem(i))) { slot=i; break; }
                check(h,slot>=0,"Picked-up stack missing from owner inventory");
                carried=owner.player.getInventory().removeItemNoUpdate(slot);
                check(h,place(h,owner.player,replacement,carried),"Owner replacement failed");
                var restored=p.lock(h.getLevel(),replacement);
                check(h,restored==lock && restored.trusted.contains(friend),"Replacement lost owner/trust");
                check(h,((Container)h.getLevel().getBlockEntity(replacement)).getItem(0).getCount()==7,"Replacement changed contents");
                ItemStack copied=new ItemStack(Blocks.SHULKER_BOX); Protection.mark(copied,lock.id);
                check(h,!place(h,owner.player,replacement.east(),copied),"Duplicate identity placement succeeded");
            } finally { cleanup(owner.player.getUUID()); }
        }
        h.succeed();
    }

    @GameTest
    public void explosionDoesNotDropProtectedContents(GameTestHelper h) {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            protect(h,pos,owner);
            ((Container)h.getLevel().getBlockEntity(pos)).setItem(0,new ItemStack(Items.DIAMOND,5));
            new net.minecraft.world.level.ServerExplosion(h.getLevel(),null,null,null,Vec3.atCenterOf(pos),3,false,
                    net.minecraft.world.level.Explosion.BlockInteraction.DESTROY).explode();
            check(h,h.getLevel().getBlockState(pos).is(Blocks.BARREL),"Explosion destroyed locked barrel");
            check(h,((Container)h.getLevel().getBlockEntity(pos)).getItem(0).getCount()==5,"Explosion changed contents");
            check(h,h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3),e -> e.getItem().is(Items.DIAMOND)).isEmpty(),"Explosion duplicated contents into drops");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void fabricTransferCannotExtractProtectedInventory(GameTestHelper h) {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            Container container=(Container)h.getLevel().getBlockEntity(pos);
            container.setItem(0,new ItemStack(Items.DIAMOND,3));
            var storage=ContainerStorage.of(container,null);
            try(Transaction t=Transaction.openOuter()) { check(h,storage.extract(ItemVariant.of(Items.DIAMOND),1,t)==1,"Public Fabric extraction denied"); }
            protect(h,pos,owner);
            try(Transaction t=Transaction.openOuter()) { check(h,storage.extract(ItemVariant.of(Items.DIAMOND),1,t)==0,"Fabric transfer bypassed lock"); t.commit(); }
            check(h,container.getItem(0).getCount()==3,"Denied Fabric extraction mutated source");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void optimizedHopperTickRespectsLockChangesAndPublicOutput(GameTestHelper h) {
        UUID owner=UUID.randomUUID();
        BlockPos sourcePos=at(h,1,3,1), hopperPos=sourcePos.below(), destination=hopperPos.east();
        try {
            h.getLevel().setBlockAndUpdate(sourcePos,Blocks.BARREL.defaultBlockState());
            h.getLevel().setBlockAndUpdate(hopperPos,Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST));
            h.getLevel().setBlockAndUpdate(destination,Blocks.BARREL.defaultBlockState());
            var source=(Container)h.getLevel().getBlockEntity(sourcePos);
            var hopper=(HopperBlockEntity)h.getLevel().getBlockEntity(hopperPos);
            var output=(Container)h.getLevel().getBlockEntity(destination);
            source.setItem(0,new ItemStack(Items.DIAMOND,9));
            for(int i=0;i<9;i++) HopperBlockEntity.pushItemsTick(h.getLevel(),hopperPos,hopper.getBlockState(),hopper);
            check(h,source.getItem(0).getCount()<9,"Public hopper did not operate");
            protect(h,sourcePos,owner);
            int count=source.getItem(0).getCount();
            for(int i=0;i<16;i++) HopperBlockEntity.pushItemsTick(h.getLevel(),hopperPos,hopper.getBlockState(),hopper);
            check(h,source.getItem(0).getCount()==count,"Cached hopper extracted after source was locked");
            protect(h,hopperPos,owner);
            for(int i=0;i<24;i++) HopperBlockEntity.pushItemsTick(h.getLevel(),hopperPos,hopper.getBlockState(),hopper);
            check(h,source.getItem(0).getCount()<count,"Same-owner hopper did not resume after being locked");
            check(h,!output.isEmpty(),"Locked hopper could not push into public destination");
            int total=source.countItem(Items.DIAMOND)+hopper.countItem(Items.DIAMOND)+output.countItem(Items.DIAMOND);
            check(h,total==9,"Hopper pipeline did not conserve items");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void untrustedChestExpansionDeniedAndSplitKeepsLock(GameTestHelper h) {
        try(Client owner=client(h); Client stranger=client(h)) {
            BlockPos pos=at(h,1,2,1), other=pos.east();
            try {
                h.getLevel().setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH));
                var lock=protect(h,pos,owner.player.getUUID());
                stranger.player.setYRot(0);
                check(h,!place(h,stranger.player,other,new ItemStack(Blocks.CHEST)),"Stranger expanded locked chest");
                check(h,h.getLevel().getBlockState(other).isAir(),"Denied expansion changed world");
                lock.trusted.add(stranger.player.getUUID());
                check(h,place(h,stranger.player,other,new ItemStack(Blocks.CHEST)),"Trusted expansion denied");
                check(h,!stranger.player.gameMode.destroyBlock(other),"Trusted player broke added half");
                check(h,owner.player.gameMode.destroyBlock(pos),"Owner could not break first half");
                check(h,Protection.active.lock(h.getLevel(),other)==lock && lock.positions.size()==1,"Split lost survivor protection");
            } finally { cleanup(owner.player.getUUID()); }
        }
        h.succeed();
    }

    @GameTest
    public void hopperMinecartCannotExtractLockedStorage(GameTestHelper h) {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,3,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            var inventory=(Container)h.getLevel().getBlockEntity(pos);
            inventory.setItem(0,new ItemStack(Items.DIAMOND,4));
            protect(h,pos,owner);
            var minecart=new net.minecraft.world.entity.vehicle.minecart.MinecartHopper(net.minecraft.world.entity.EntityTypes.HOPPER_MINECART,h.getLevel());
            minecart.setPos(pos.getX()+.5,pos.getY()-1,pos.getZ()+.5);
            check(h,!minecart.suckInItems(),"Hopper minecart bypassed lock");
            check(h,inventory.getItem(0).getCount()==4,"Minecart denial changed contents");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void denialIsPrivateRateLimitedAndIndependentOfLabels(GameTestHelper h) {
        try(Client owner=client(h); Client stranger=client(h)) {
            BlockPos pos=at(h,1,2,1);
            try {
                h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
                protect(h,pos,owner.player.getUUID());
                Protection.active.state.names.put(owner.player.getUUID(),"QuickOwner");
                var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false);
                for(int i=0;i<5;i++) stranger.player.gameMode.useItemOn(stranger.player,h.getLevel(),ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit);
                var messages=stranger.packets.stream().filter(x -> x instanceof ClientboundSystemChatPacket).map(x -> (ClientboundSystemChatPacket)x).toList();
                check(h,messages.size()==1 && messages.getFirst().content().getString().equals("This container is locked by QuickOwner."),"Denial text or rate limit incorrect");
                check(h,owner.packets.stream().noneMatch(x -> x instanceof ClientboundSystemChatPacket),"Denial leaked to owner");
                check(h,stranger.player.containerMenu==stranger.player.inventoryMenu,"Denied player opened menu");
            } finally { cleanup(owner.player.getUUID()); }
        }
        h.succeed();
    }

    @GameTest
    public void adminOverrideRequiresAllowlistAndConsoleSource(GameTestHelper h) {
        try(Client owner=client(h); Client admin=client(h)) {
            var p=Protection.active;
            BlockPos pos=at(h,1,2,1);
            try {
                h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
                var lock=protect(h,pos,owner.player.getUUID());
                check(h,!p.access(admin.player,h.getLevel(),pos),"Unlisted admin bypassed lock");
                p.config.adminUuids.add(admin.player.getUUID());
                check(h,p.access(admin.player,h.getLevel(),pos),"Allowlisted admin denied access");
                ItemStack invalid=new ItemStack(Blocks.SHULKER_BOX); Protection.mark(invalid,UUID.randomUUID());
                check(h,p.pickup(invalid,admin.player.getUUID()) && !p.pickup(invalid,owner.player.getUUID()),"Invalid-token recovery pickup policy incorrect");
                var node=p.server.getCommands().getDispatcher().getRoot().getChild("quicklockadmin");
                check(h,node.canUse(admin.player.createCommandSourceStack()),"Admin recovery command unavailable");
                check(h,node.canUse(p.server.createCommandSourceStack()),"Console recovery unavailable");
                check(h,!node.canUse(p.server.createCommandSourceStack().withSource(net.minecraft.commands.CommandSource.NULL)),"Non-console command source inherited recovery");
                check(h,admin.player.gameMode.destroyBlock(pos),"Admin recovery break denied");
                check(h,p.lock(h.getLevel(),pos)==null,"Admin break left stale lock");
            } finally { p.config.adminUuids.remove(admin.player.getUUID()); cleanup(owner.player.getUUID()); }
        }
        h.succeed();
    }

    @GameTest
    public void chunkWitnessAndTokenSurviveVanillaSerialization(GameTestHelper h) {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.SHULKER_BOX.defaultBlockState());
            var lock=protect(h,pos,owner);
            var entity=h.getLevel().getBlockEntity(pos);
            var tag=entity.saveWithFullMetadata(h.getLevel().registryAccess());
            check(h,tag.getStringOr("quicklock_lock","").equals(lock.id.toString()),"Chunk lost lock witness");
            var reloaded=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,entity.getBlockState(),tag,h.getLevel().registryAccess());
            check(h,reloaded!=null,"Block entity failed vanilla deserialize");
            reloaded.setLevel(h.getLevel());
            check(h,((BlockWitness)reloaded).quicklock$lock().equals(lock.id.toString()),"Witness lost after load");
            ItemStack item=new ItemStack(Blocks.SHULKER_BOX); Protection.mark(item,lock.id);
            var ops=h.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            var encoded=ItemStack.CODEC.encodeStart(ops,item).getOrThrow();
            var decoded=ItemStack.CODEC.parse(ops,encoded).getOrThrow();
            check(h,lock.id.equals(Protection.token(decoded)),"Vanilla item serialization lost identity");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void copperGolemChecksTargetAndMutation(GameTestHelper h) throws Exception {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
            var container=(Container)h.getLevel().getBlockEntity(pos);
            container.setItem(0,new ItemStack(Items.DIAMOND,3));
            var behavior=new net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers(1,s -> true,s -> true,8,8,Map.of(),m -> {},t -> false);
            var target=net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget.tryCreatePossibleTarget(pos,h.getLevel());
            var selector=behavior.getClass().getDeclaredMethod("isContainerLocked",target.getClass());
            selector.setAccessible(true);
            check(h,!(boolean)selector.invoke(behavior,target),"Public chest treated as golem-locked");
            var mutation=behavior.getClass().getDeclaredMethod("doReachedTargetInteraction",net.minecraft.world.entity.PathfinderMob.class,Container.class,
                    java.util.function.BiConsumer.class,java.util.function.BiConsumer.class,java.util.function.BiConsumer.class,java.util.function.BiConsumer.class);
            mutation.setAccessible(true);
            var golem=new net.minecraft.world.entity.animal.golem.CopperGolem(net.minecraft.world.entity.EntityTypes.COPPER_GOLEM,h.getLevel());
            boolean[] invoked={false};
            java.util.function.BiConsumer<net.minecraft.world.entity.PathfinderMob,Container> action=(m,c) -> invoked[0]=true;
            mutation.invoke(behavior,golem,container,action,action,action,action);
            check(h,invoked[0],"Public golem interaction suppressed");
            protect(h,pos,owner);
            check(h,(boolean)selector.invoke(behavior,target),"Locked chest remained golem target");
            invoked[0]=false;
            mutation.invoke(behavior,golem,container,action,action,action,action);
            check(h,!invoked[0],"Golem interaction executed after target was locked");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void lockedShulkerCannotBeDestroyedByPiston(GameTestHelper h) {
        UUID owner=UUID.randomUUID(); BlockPos pos=at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos,Blocks.SHULKER_BOX.defaultBlockState());
            var state=h.getLevel().getBlockState(pos);
            check(h,net.minecraft.world.level.block.piston.PistonBaseBlock.isPushable(state,h.getLevel(),pos,Direction.EAST,true,Direction.EAST),"Public shulker was not piston-destroyable");
            protect(h,pos,owner);
            check(h,!net.minecraft.world.level.block.piston.PistonBaseBlock.isPushable(state,h.getLevel(),pos,Direction.EAST,true,Direction.EAST),"Piston can destroy locked shulker");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void persistedFixtureSurvivesIndependentServerRuns(GameTestHelper h) throws Exception {
        var p=Protection.active;
        UUID owner=UUID.nameUUIDFromBytes("quicklock-restart-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID guest=UUID.nameUUIDFromBytes("quicklock-restart-guest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BlockPos pos=new BlockPos(0,100,0), shulkerPos=pos.east(2);
        var marker=p.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("quicklock-test-fixture");
        h.getLevel().getChunkAt(pos);
        if(java.nio.file.Files.exists(marker)) {
            var lock=p.lock(h.getLevel(),pos);
            check(h,lock!=null && lock.owner.equals(owner),"Restart lost placed lock");
            check(h,lock.trusted.contains(guest) && p.state.globalTrust(lock,guest),"Restart lost independent trust grants");
            check(h,p.state.viewers.contains(owner),"Restart lost view preference");
            var inventory=(Container)h.getLevel().getBlockEntity(pos);
            check(h,inventory.getItem(0).getCount()==11,"Restart changed protected contents");
            var token=Protection.token(inventory.getItem(1));
            var portable=p.state.locks.get(token);
            check(h,portable!=null && portable.portable && portable.owner.equals(owner) && portable.trusted.contains(guest),"Restart lost portable shulker identity/trust");
            QuickLock.LOGGER.info("Restart fixture verified from a previous dedicated-server process");
        } else {
            h.getLevel().setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            var lock=protect(h,pos,owner); lock.trusted.add(guest);
            p.state.globalTrust.put(owner,new HashSet<>(Set.of(guest)));
            p.state.viewers.add(owner);
            var inventory=(Container)h.getLevel().getBlockEntity(pos);
            inventory.setItem(0,new ItemStack(Items.DIAMOND,11));
            h.getLevel().setBlockAndUpdate(shulkerPos,Blocks.SHULKER_BOX.defaultBlockState());
            var portable=protect(h,shulkerPos,owner); portable.trusted.add(guest);
            p.breaking.set(new Protection.Break(Protection.key(h.getLevel(),shulkerPos),portable));
            try { h.getLevel().setBlockAndUpdate(shulkerPos,Blocks.AIR.defaultBlockState()); }
            finally { p.breaking.remove(); }
            ItemStack stack=new ItemStack(Blocks.SHULKER_BOX); Protection.mark(stack,portable.id);
            inventory.setItem(1,stack);
            p.save();
            java.nio.file.Files.writeString(marker,"Fixture created by QuickLock game tests; rerun to verify a real process restart.\n");
            QuickLock.LOGGER.info("Restart fixture created; the next independent test server verifies persistence");
        }
        h.succeed();
    }

    @GameTest
    public void explicitAllowlist(GameTestHelper h) {
        for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.FURNACE, Blocks.BLAST_FURNACE,
                Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.CRAFTER, Blocks.SHULKER_BOX)) {
            check(h, Protection.eligible(block.defaultBlockState()), "Missing eligible container: " + block);
        }
        for (Block block : Blocks.DYED_SHULKER_BOX.asList()) check(h, Protection.eligible(block.defaultBlockState()), "Missing shulker color");
        for (Block block : List.of(Blocks.ENDER_CHEST, Blocks.STONE, Blocks.CRAFTING_TABLE, Blocks.DECORATED_POT)) {
            check(h, !Protection.eligible(block.defaultBlockState()), "Unexpected eligible block: " + block);
        }
        h.succeed();
    }

    @GameTest
    public void placementReservesButDoesNotLock(GameTestHelper h) {
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = at(h,1,2,1);
        check(h, place(h, owner, pos, new ItemStack(Blocks.BARREL)), "Placement failed");
        var p = Protection.active;
        String key = Protection.key(h.getLevel(), pos);
        check(h, owner.getUUID().equals(p.state.placements.get(key).placer), "Placement owner missing");
        check(h, p.lock(h.getLevel(), pos) == null, "Placement unexpectedly locked inventory");
        check(h, h.getLevel().destroyBlock(pos, true), "Public container could not be destroyed");
        check(h, !p.state.placements.containsKey(key), "Destruction left stale placement");
        h.succeed();
    }

    @GameTest
    public void hopperCannotStealButSameOwnerCanPull(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        BlockPos chestPos = at(h,1,3,1), hopperPos = chestPos.below();
        try {
            h.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            h.getLevel().setBlockAndUpdate(hopperPos, Blocks.HOPPER.defaultBlockState());
            protect(h, chestPos, owner);
            Container chest = (Container) h.getLevel().getBlockEntity(chestPos);
            HopperBlockEntity hopper = (HopperBlockEntity) h.getLevel().getBlockEntity(hopperPos);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 4));
            check(h, !HopperBlockEntity.suckInItems(h.getLevel(), hopper), "Public hopper stole from locked chest");
            check(h, chest.getItem(0).getCount() == 4, "Denied pull mutated source");
            var p = Protection.active;
            String hopperKey = Protection.key(h.getLevel(), hopperPos);
            p.state.placed(hopperKey, UUID.randomUUID(), Protection.blockId(h.getLevel().getBlockState(hopperPos)));
            var foreign = p.state.protect(List.of(hopperKey), p.state.placements.get(hopperKey).placer, false, false);
            check(h, !HopperBlockEntity.suckInItems(h.getLevel(), hopper), "Foreign locked hopper stole items");
            p.state.unlock(foreign);
            p.state.placements.get(hopperKey).placer = owner;
            p.state.protect(List.of(hopperKey), owner, false, false);
            check(h, HopperBlockEntity.suckInItems(h.getLevel(), hopper), "Same-owner locked hopper could not pull");
            check(h, chest.getItem(0).getCount() == 3 && hopper.getItem(0).getCount() == 1, "Transfer did not conserve items");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void protectedDestructionIsRejectedBeforeDrops(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        BlockPos pos = at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            protect(h,pos,owner);
            check(h, !h.getLevel().destroyBlock(pos, true), "Locked block destroyed");
            check(h, !h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()), "Direct removal bypassed lock");
            check(h, h.getLevel().getBlockState(pos).is(Blocks.BARREL), "Locked block lost");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void trustControlsUseButNotManagement(GameTestHelper h) {
        Player owner = h.makeMockPlayer(GameType.SURVIVAL), friend = h.makeMockPlayer(GameType.SURVIVAL), stranger = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = at(h,1,2,1);
        try {
            h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            var lock = protect(h,pos,owner.getUUID());
            var container = (net.minecraft.world.level.block.entity.BaseContainerBlockEntity) h.getLevel().getBlockEntity(pos);
            lock.trusted.add(friend.getUUID());
            check(h, container.canOpen(owner), "Owner denied");
            check(h, container.canOpen(friend), "Trusted player denied");
            check(h, !container.canOpen(stranger), "Untrusted player opened lock");
            check(h, !Protection.active.state.manage(lock, friend.getUUID(), false), "Trust granted management");
        } finally { cleanup(owner.getUUID()); }
        h.succeed();
    }

    @GameTest
    public void shulkerTokenBlocksForeignPickupAndAutomation(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        BlockPos pos = at(h,1,3,1), hopperPos = pos.below();
        try {
            h.getLevel().setBlockAndUpdate(pos, Blocks.SHULKER_BOX.defaultBlockState());
            var lock = protect(h,pos,owner);
            ItemStack stack = new ItemStack(Blocks.SHULKER_BOX);
            Protection.mark(stack, lock.id);
            Protection.active.breaking.set(new Protection.Break(Protection.key(h.getLevel(), pos), lock));
            try { h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); }
            finally { Protection.active.breaking.remove(); }
            check(h, Protection.active.pickup(stack, owner), "Owner cannot pick up portable shulker");
            check(h, !Protection.active.pickup(stack, UUID.randomUUID()), "Foreign pickup allowed");
            h.getLevel().setBlockAndUpdate(hopperPos, Blocks.HOPPER.defaultBlockState());
            ItemEntity item = new ItemEntity(h.getLevel(),pos.getX()+.5,pos.getY()+.1,pos.getZ()+.5,stack);
            check(h, !HopperBlockEntity.addItem((Container)h.getLevel().getBlockEntity(hopperPos),item), "Hopper collected locked shulker");
            check(h, item.getItem().getCount()==1, "Denied collection consumed item");
        } finally { cleanup(owner); }
        h.succeed();
    }

    @GameTest
    public void trustedExpansionKeepsOriginalOwnership(GameTestHelper h) {
        Player owner = h.makeMockPlayer(GameType.SURVIVAL), friend = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = at(h,1,2,1), other = pos.east();
        try {
            h.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
            var lock = protect(h,pos,owner.getUUID());
            lock.trusted.add(friend.getUUID());
            friend.setYRot(0);
            check(h, place(h,friend,other,new ItemStack(Blocks.CHEST)), "Trusted expansion failed");
            check(h, Protection.partner(h.getLevel(),pos)!=null, "Chest did not merge");
            check(h, Protection.active.lock(h.getLevel(),other)==lock, "Added half did not inherit lock");
            check(h, lock.positions.size()==2 && lock.owner.equals(owner.getUUID()), "Expansion changed ownership");
        } finally { cleanup(owner.getUUID()); }
        h.succeed();
    }
}
