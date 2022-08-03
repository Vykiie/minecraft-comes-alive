package mca.entity.ai.relationship.family;

import mca.entity.VillagerEntityMCA;
import mca.entity.ai.relationship.Gender;
import mca.util.NbtHelper;
import mca.util.WorldUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class FamilyTree extends PersistentState {
    private static final String DATA_ID = "MCA-FamilyTree";

    private final Map<UUID, FamilyTreeNode> entries;

    public static FamilyTree get(ServerWorld world) {
        return WorldUtils.loadData(world.getServer().getOverworld(), FamilyTree::new, FamilyTree::new, DATA_ID);
    }

    FamilyTree(ServerWorld world) {
        entries = new HashMap<>();
    }

    FamilyTree(NbtCompound nbt) {
        entries = NbtHelper.toMap(nbt, UUID::fromString, (id, element) -> new FamilyTreeNode(this, id, (NbtCompound)element));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        return NbtHelper.fromMap(nbt, entries, UUID::toString, FamilyTreeNode::save);
    }

    public Optional<FamilyTreeNode> getOrEmpty(@Nullable UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    public Stream<FamilyTreeNode> getAllWithName(String name) {
        return entries.values().stream().filter(n -> n.getName().equals(name));
    }

    @NotNull
    public FamilyTreeNode getOrCreate(Entity entity) {
        return entries.computeIfAbsent(entity.getUuid(), uuid -> createEntry(
                entity.getUuid(),
                entity.getName().getString(),
                entity instanceof VillagerEntityMCA ? ((VillagerEntityMCA)entity).getGenetics().getGender() : Gender.MALE,
                entity instanceof PlayerEntity));
    }

    public void remove(UUID id) {
        entries.remove(id);
        markDirty();
    }

    @NotNull
    public FamilyTreeNode getOrCreate(UUID id, String name, Gender gender) {
        return getOrCreate(id, name, gender, false);
    }

    @NotNull
    public FamilyTreeNode getOrCreate(UUID id, String name, Gender gender, boolean isPlayer) {
        return entries.computeIfAbsent(id, uuid -> createEntry(uuid, name, gender, isPlayer));
    }

    private FamilyTreeNode createEntry(UUID uuid, String name, Gender gender, boolean isPlayer) {
        markDirty();
        return new FamilyTreeNode(this,
                uuid,
                name,
                isPlayer,
                gender,
                Util.NIL_UUID,
                Util.NIL_UUID
        );
    }
}
