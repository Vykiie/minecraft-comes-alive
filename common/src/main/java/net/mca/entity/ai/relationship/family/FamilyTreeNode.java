package net.mca.entity.ai.relationship.family;

import net.mca.MCA;
import net.mca.entity.ai.relationship.EntityRelationship;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.ai.relationship.RelationshipState;
import net.mca.util.NbtElementCompat;
import net.mca.util.NbtHelper;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.MutableText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.registry.Registry;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

public final class FamilyTreeNode implements Serializable {
    private static final long serialVersionUID = -7307057982785253721L;

    private final boolean isPlayer;

    private Gender gender;

    private String name;
    private String profession = Registry.VILLAGER_PROFESSION.getId(VillagerProfession.NONE).toString();

    private final UUID id;

    private UUID father;
    private UUID mother;

    private UUID partner = Util.NIL_UUID;
    private RelationshipState relationshipState = RelationshipState.SINGLE;

    private boolean deceased;

    private final Set<UUID> children = new HashSet<>();

    private transient final FamilyTree rootNode;

    public FamilyTreeNode(FamilyTree rootNode, UUID id, String name, boolean isPlayer, Gender gender, UUID father, UUID mother) {
        this.rootNode = rootNode;
        this.id = id;
        this.name = name;
        this.isPlayer = isPlayer;
        this.gender = gender;
        this.father = father;
        this.mother = mother;
    }

    public FamilyTreeNode(FamilyTree rootNode, UUID id, NbtCompound nbt) {
        this(
                rootNode,
                id,
                nbt.getString("name"),
                nbt.getBoolean("isPlayer"),
                Gender.byId(nbt.getInt("gender")),
                nbt.getUuid("father"),
                nbt.getUuid("mother")
        );
        children.addAll(NbtHelper.toList(nbt.getList("children", NbtElementCompat.COMPOUND_TYPE), c -> ((NbtCompound)c).getUuid("uuid")));
        profession = nbt.getString("profession");
        deceased = nbt.getBoolean("isDeceased");
        if (nbt.containsUuid("spouse")) {
            partner = nbt.getUuid("spouse");
        }
        relationshipState = RelationshipState.byId(nbt.getInt("marriageState"));
    }

    public UUID id() {
        return id;
    }

    private void markDirty() {
        if (rootNode != null) {
            rootNode.markDirty();
        }
    }

    public boolean isDeceased() {
        return deceased;
    }

    public void setDeceased(boolean deceased) {
        this.deceased = deceased;
        markDirty();
    }

    public void setName(String name) {
        this.name = name;
        markDirty();
    }

    public String getName() {
        return name;
    }

    public void setProfession(VillagerProfession profession) {
        this.profession = Registry.VILLAGER_PROFESSION.getId(profession).toString();
        markDirty();
    }

    public VillagerProfession getProfession() {
        return Registry.VILLAGER_PROFESSION.get(getProfessionId());
    }

    public Identifier getProfessionId() {
        return Identifier.tryParse(profession);
    }

    public String getProfessionName() {
        String professionName = (
                getProfessionId().getNamespace().equalsIgnoreCase("minecraft") ?
                        (getProfessionId().getPath().equals("none") ? "mca.none" : getProfessionId().getPath()) :
                        getProfessionId().toString()
        ).replace(":", ".");

        return MCA.isBlankString(professionName) ? "mca.none" : professionName;
    }

    public MutableText getProfessionText() {
        return new TranslatableText("entity.minecraft.villager." + getProfessionName());
    }

    public boolean isPlayer() {
        return isPlayer;
    }

    public Gender gender() {
        return gender;
    }

    public UUID father() {
        return father;
    }

    public UUID mother() {
        return mother;
    }

    /**
     * Id of the last this entity's most recent partner.
     */
    public UUID partner() {
        return partner;
    }

    public RelationshipState getRelationshipState() {
        return relationshipState;
    }

    //debug usage only
    public void setRelationshipState(RelationshipState relationshipState) {
        this.relationshipState = relationshipState;
    }

    public void updatePartner(@Nullable Entity newPartner, @Nullable RelationshipState state) {
        //cancel relationship with previous partner
        if (!this.partner.equals(Util.NIL_UUID) && (newPartner == null || !this.partner.equals(newPartner.getUuid()))) {
            getRoot().getOrEmpty(this.partner).ifPresent(n -> {
                if (n.relationshipState == RelationshipState.ENGAGED) {
                    if (isPlayer) {
                        //todo PlayerSaveData#sendLetterOfEngagement (Likely refactor to a new event, onEngagement, in 7.4.0)
                    }
                }
                n.partner = Util.NIL_UUID;
                n.relationshipState = RelationshipState.SINGLE;
            });
        }

        this.partner = newPartner == null ? Util.NIL_UUID : newPartner.getUuid();
        this.relationshipState = state == null && newPartner == null ? RelationshipState.SINGLE : state;

        // ensure the family tree has an entry
        if (newPartner != null) {
            rootNode.getOrCreate(newPartner);
        }

        rootNode.markDirty();
    }

    public void updatePartner(FamilyTreeNode spouse) {
        this.partner = spouse.id();
        this.relationshipState = spouse.isPlayer ? RelationshipState.MARRIED_TO_PLAYER : RelationshipState.MARRIED_TO_VILLAGER;
        markDirty();
    }

    public Set<UUID> children() {
        return children;
    }

    public Stream<UUID> streamChildren() {
        return children.stream().filter(FamilyTreeNode::isValid);
    }

    public Stream<UUID> streamParents() {
        return Stream.of(father(), mother()).filter(FamilyTreeNode::isValid);
    }

    /**
     * All persons who share at least one common parent
     */
    public Set<UUID> siblings() {
        Set<UUID> siblings = new HashSet<>();

        streamParents().forEach(parent -> getRoot().getOrEmpty(parent).ifPresent(p -> gatherChildren(p, siblings, 1)));

        return siblings;
    }

    public Stream<UUID> getChildren() {
        return getRelatives(0, 1);
    }

    // returns indirect relatives like siblings and their respective family
    // potential slow for large families, getRelatives() is preferred if indirect family members are not relevant
    public Stream<UUID> getAllRelatives(int depth) {
        Set<UUID> family = new HashSet<>();

        //recursive family fetching
        Set<UUID> todo = new HashSet<>();
        todo.add(id);
        for (int d = 0; d < depth; d++) {
            Set<UUID> nextTodo = new HashSet<>();
            for (UUID uuid : todo) {
                if (!family.contains(uuid)) {
                    rootNode.getOrEmpty(uuid).ifPresent(node -> {
                        family.add(uuid);

                        //add parents and children
                        node.streamParents().forEach(nextTodo::add);
                        node.streamChildren().forEach(nextTodo::add);
                    });
                }
            }
            todo = nextTodo;
        }

        //the caller is not meant
        family.remove(id);

        return family.stream();
    }

    // returns all direct relatives (parents, grandparents, children, grandchildren)
    public Stream<UUID> getRelatives(int parentDepth, int childrenDepth) {
        Set<UUID> family = new HashSet<>();

        //fetch parents and children
        gatherParents(this, family, parentDepth);
        gatherChildren(this, family, childrenDepth);

        //and the caller is not meant either
        family.remove(id);

        return family.stream();
    }

    public boolean isRelative(UUID with) {
        return getAllRelatives(9).anyMatch(with::equals);
    }

    public Stream<FamilyTreeNode> getParents() {
        return lookup(streamParents());
    }

    /**
     * All persons who share at least one common parent
     */
    public Stream<FamilyTreeNode> getSiblings() {
        return lookup(siblings().stream());
    }

    public Stream<FamilyTreeNode> lookup(Stream<UUID> uuids) {
        return uuids.map(getRoot()::getOrEmpty).filter(Optional::isPresent).map(Optional::get);
    }

    public boolean isParent(UUID id) {
        return streamParents().anyMatch(parent -> parent.equals(id));
    }

    public boolean isGrandParent(UUID id) {
        return getParents().anyMatch(parent -> parent.isParent(id));
    }

    public boolean isUncle(UUID id) {
        return getParents().flatMap(parent -> parent.siblings().stream()).distinct().anyMatch(id::equals);
    }

    public void addChild(UUID child) {
        children.add(child);
    }

    public FamilyTree getRoot() {
        return rootNode;
    }

    public boolean assignParents(EntityRelationship one, EntityRelationship two) {
        return assignParent(one.getFamilyEntry()) | assignParent(two.getFamilyEntry());
    }

    public boolean assignParent(FamilyTreeNode parent) {
        int parents = (isValid(father) ? 1 : 0) + (isValid(mother) ? 1 : 0);

        if (parents == 1) {
            //fill up last slot, independent on gender
            if (!isValid(father)) {
                return setFather(parent);
            } else if (!isValid(mother)) {
                return setMother(parent);
            }
        } else {
            //fill up gender respective slot
            if (parent.gender() == Gender.MALE) {
                return setFather(parent);
            } else {
                return setMother(parent);
            }
        }
        return true;
    }

    public boolean setFather(FamilyTreeNode parent) {
        father = parent.id();
        parent.children().add(id);
        markDirty();
        return true;
    }

    public boolean setMother(FamilyTreeNode parent) {
        mother = parent.id();
        parent.children().add(id);
        markDirty();
        return true;
    }

    public boolean removeFather() {
        if (isValid(father)) {
            rootNode.getOrEmpty(father).ifPresent(e -> e.children.remove(this.id));
            father = Util.NIL_UUID;
            markDirty();
            return true;
        } else {
            return false;
        }
    }

    public boolean removeMother() {
        if (isValid(mother)) {
            rootNode.getOrEmpty(mother).ifPresent(e -> e.children.remove(this.id));
            mother = Util.NIL_UUID;
            markDirty();
            return true;
        } else {
            return false;
        }
    }

    public void setGender(Gender gender) {
        this.gender = gender;
        markDirty();
    }

    // entries with these conditions are usually generated
    public boolean probablyGenerated() {
        return mother.equals(Util.NIL_UUID) && father.equals(Util.NIL_UUID) && children.size() == 1 && deceased && !isPlayer();
    }

    // true if there is at least one non-generated relative
    public boolean willBeRemembered() {
        if (!children.isEmpty()) {
            return true;
        }
        if (!partner.equals(Util.NIL_UUID)) {
            return true;
        }
        return !getParents().allMatch(FamilyTreeNode::probablyGenerated);
    }

    public static boolean isValid(@Nullable UUID uuid) {
        return uuid != null && !Util.NIL_UUID.equals(uuid);
    }

    private static void gatherParents(FamilyTreeNode current, Set<UUID> family, int depth) {
        gather(current, family, depth, FamilyTreeNode::streamParents);
    }

    private static void gatherChildren(FamilyTreeNode current, Set<UUID> family, int depth) {
        gather(current, family, depth, FamilyTreeNode::streamChildren);
    }

    private static void gather(@Nullable FamilyTreeNode entry, Set<UUID> output, int depth, Function<FamilyTreeNode, Stream<UUID>> walker) {
        if (entry == null || depth <= 0) {
            return;
        }
        walker.apply(entry).forEach(id -> {
            if (!Util.NIL_UUID.equals(id)) {
                output.add(id); //zero UUIDs are no real members
            }
            if (depth > 1) {
                entry.getRoot().getOrEmpty(id).ifPresent(e -> gather(e, output, depth - 1, walker));
            }
        });
    }

    public NbtCompound save() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("name", name);
        nbt.putBoolean("isPlayer", isPlayer);
        nbt.putBoolean("isDeceased", deceased);
        nbt.putInt("gender", gender.getId());
        nbt.putUuid("father", father);
        nbt.putUuid("mother", mother);
        nbt.putUuid("spouse", partner);
        nbt.putInt("marriageState", relationshipState.ordinal());
        nbt.put("children", NbtHelper.fromList(children, child -> {
            NbtCompound n = new NbtCompound();
            n.putUuid("uuid", child);
            return n;
        }));
        return nbt;
    }
}
