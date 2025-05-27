package net.minecraft.world.level.block;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// Imports for potential custom HUD (currently commented out in tick method)
// import net.minecraft.server.level.ServerPlayer;
// import net.minecraft.network.chat.Component;

/************************************************************************************
 * Part 1. Configuration & Constants
 ************************************************************************************/
public class FireBlock extends BaseFireBlock {

   // --- User Edits: Age Configuration ---
   public static final int MAX_AGE = 45; // Vanilla: 15
   public static final int ORIGINAL_MAX_AGE = 15; // For scaling comparisons
   public static final int SCALE_AGE = MAX_AGE / ORIGINAL_MAX_AGE; // Should be 3 if MAX_AGE is 45

   public static final IntegerProperty AGE = IntegerProperty.create("age", 0, MAX_AGE);
   // --- End User Edits ---

   public static final BooleanProperty NORTH = PipeBlock.NORTH;
   public static final BooleanProperty EAST = PipeBlock.EAST;
   public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
   public static final BooleanProperty WEST = PipeBlock.WEST;
   public static final BooleanProperty UP = PipeBlock.UP;
   private static final Map<Direction, BooleanProperty> PROPERTY_BY_DIRECTION = PipeBlock.PROPERTY_BY_DIRECTION.entrySet().stream().filter((p_53467_) -> {
      return p_53467_.getKey() != Direction.DOWN;
   }).collect(Util.toMap());

   // --- User Edits: Custom Fields ---
   // private static int fireTickCounter = 0; // For HUD, logic commented out in tick()
   private static final boolean ENABLE_VERTICAL_FIRE_SPREAD = true; // Toggle for 3D spread aspects
   // --- End User Edits ---

   private static final VoxelShape UP_AABB = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape WEST_AABB = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
   private static final VoxelShape EAST_AABB = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape NORTH_AABB = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
   private static final VoxelShape SOUTH_AABB = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
   private final Map<BlockState, VoxelShape> shapesCache;

   // These constants are from vanilla, not directly used in the new probability formulas
   // but retained for context if any other part of the code (e.g. BaseFireBlock) uses them.
   // private static final int IGNITE_INSTANT = 60;
   // private static final int IGNITE_EASY = 30;
   // private static final int IGNITE_MEDIUM = 15;
   // private static final int IGNITE_HARD = 5;
   // private static final int BURN_INSTANT = 100;
   // private static final int BURN_EASY = 60;
   // private static final int BURN_MEDIUM = 20;
   // private static final int BURN_HARD = 5;

   private final Object2IntMap<Block> igniteOdds = new Object2IntOpenHashMap<>(); // How well this block can start a fire in an air block next to it
   private final Object2IntMap<Block> burnOdds = new Object2IntOpenHashMap<>();   // How well this block can burn and be replaced by fire

   /************************************************************************************
    * Part 2. State Management & Visual Representation
    ************************************************************************************/
   public FireBlock(BlockBehaviour.Properties properties) {
      super(properties, 1.0F); // Second parameter is fireDamage
      this.registerDefaultState(this.stateDefinition.any()
              .setValue(AGE, 0)
              .setValue(NORTH, false)
              .setValue(EAST, false)
              .setValue(SOUTH, false)
              .setValue(WEST, false)
              .setValue(UP, false));
      this.shapesCache = ImmutableMap.copyOf(this.stateDefinition.getPossibleStates().stream()
              .filter(state -> state.getValue(AGE) == 0)
              .collect(Collectors.toMap(Function.identity(), FireBlock::calculateShape)));
   }

   private static VoxelShape calculateShape(BlockState blockState) {
      VoxelShape shape = Shapes.empty();
      if (blockState.getValue(UP)) shape = UP_AABB;
      if (blockState.getValue(NORTH)) shape = Shapes.or(shape, NORTH_AABB);
      if (blockState.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_AABB);
      if (blockState.getValue(EAST)) shape = Shapes.or(shape, EAST_AABB);
      if (blockState.getValue(WEST)) shape = Shapes.or(shape, WEST_AABB);
      return shape.isEmpty() ? DOWN_AABB : shape; // DOWN_AABB is likely defined in BaseFireBlock or Block
   }

   /************************************************************************************
    * Part 3. Functions for Updating Blocks (Largely Vanilla)
    ************************************************************************************/
   @Override
   public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
      return this.canSurvive(state, level, currentPos) ? this.getStateWithAge(level, currentPos, state.getValue(AGE)) : Blocks.AIR.defaultBlockState();
   }

   @Override
   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      // Shapes are cached only for age 0 to prevent too many states in memory.
      // Visual representation of fire age is typically handled by particles or texture animation, not different VoxelShapes per age.
      return this.shapesCache.get(state.setValue(AGE, 0));
   }

   @Override
   public BlockState getStateForPlacement(BlockPlaceContext context) {
      return this.getStateForPlacement(context.getLevel(), context.getClickedPos());
   }

   protected BlockState getStateForPlacement(BlockGetter level, BlockPos pos) {
      BlockPos belowPos = pos.below();
      BlockState belowState = level.getBlockState(belowPos);
      if (this.canBurn(belowState) || belowState.isFaceSturdy(level, belowPos, Direction.UP)) {
         return this.defaultBlockState();
      } else {
         BlockState placementState = this.defaultBlockState();
         for (Direction direction : Direction.values()) {
            BooleanProperty property = PROPERTY_BY_DIRECTION.get(direction);
            if (property != null) {
               placementState = placementState.setValue(property, this.canBurn(level.getBlockState(pos.relative(direction))));
            }
         }
         return placementState;
      }
   }

   @Override
   public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
      BlockPos belowPos = pos.below();
      return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP) || this.isValidFireLocation(level, pos);
   }

   @Override
   public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
      super.onPlace(state, level, pos, oldState, isMoving);
      level.scheduleTick(pos, this, getFireTickDelay(level.random));
   }

   /************************************************************************************
    * Part 4. Fire Behaviour and Logic
    ************************************************************************************/
   @Override
   public void tick(BlockState blockState, ServerLevel serverLevel, BlockPos blockPos, RandomSource randomSource) {
      serverLevel.scheduleTick(blockPos, this, getFireTickDelay(serverLevel.random)); // Schedule next tick

      // --- User Edit: HUD Logic (currently commented out) ---
      // fireTickCounter++;
      // for (ServerPlayer player : serverLevel.getPlayers(p -> true)) {
      //     player.displayClientMessage(Component.literal("Fire Tick Count: " + fireTickCounter), true); // True for action bar
      // }
      // --- End User Edit ---

      if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return;

      if (!blockState.canSurvive(serverLevel, blockPos)) {
         serverLevel.removeBlock(blockPos, false);
         return;
      }

      // Extinguishing conditions
      boolean isInfiniteBurnArea = serverLevel.getBlockState(blockPos.below()).is(serverLevel.dimensionType().infiniburn());
      int currentFireAge = blockState.getValue(AGE);

      // User's scaled rain extinguish chance
      float normalizedAgeForRain = (float)currentFireAge / SCALE_AGE; // Effectively maps current age back to 0-15 range for this formula
      if (!isInfiniteBurnArea && serverLevel.isRaining() && this.isNearRain(serverLevel, blockPos) &&
              randomSource.nextFloat() < (0.2F + normalizedAgeForRain * 0.03F)) {
         serverLevel.removeBlock(blockPos, false);
         return;
      }

      // Update fire's age - User's deterministic increase
      int updatedFireAge = Math.min(MAX_AGE, currentFireAge + 1);
      if (currentFireAge != updatedFireAge) {
         blockState = blockState.setValue(AGE, updatedFireAge);
         serverLevel.setBlock(blockPos, blockState, Block.UPDATE_INVISIBLE); // Flag 4 is often used, 3 = send to client & update observers
         currentFireAge = updatedFireAge; // Update for subsequent logic in this same tick
      }

      if (!isInfiniteBurnArea) {
         // Extinguish lonely fire (scaled age)
         if (!this.isValidFireLocation(serverLevel, blockPos)) {
            BlockPos belowPos = blockPos.below();
            if (!serverLevel.getBlockState(belowPos).isFaceSturdy(serverLevel, belowPos, Direction.UP) || currentFireAge > (3 * SCALE_AGE)) {
               serverLevel.removeBlock(blockPos, false);
               return;
            }
         }
         // Extinguish fully aged fire on non-flammable block (user removed randomness)
         if (currentFireAge == MAX_AGE && !this.canBurn(serverLevel.getBlockState(blockPos.below()))) {
            serverLevel.removeBlock(blockPos, false);
            return;
         }
      }

      // Spread fire
      boolean isIncreasedBurnoutBiome = serverLevel.getBiome(blockPos).is(BiomeTags.INCREASED_FIRE_BURNOUT);
      int biomeBurnoutEffect = isIncreasedBurnoutBiome ? -50 : 0;

      // Mechanism 1: Adjacent Block Burn (`checkBurnOut`)
      // Horizontal spread
      this.trySpreadToAdjacent(serverLevel, blockPos.east(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacent(serverLevel, blockPos.west(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacent(serverLevel, blockPos.north(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacent(serverLevel, blockPos.south(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);

      // Vertical spread (conditional)
      if (ENABLE_VERTICAL_FIRE_SPREAD) {
         this.trySpreadToAdjacent(serverLevel, blockPos.below(), 250 + biomeBurnoutEffect, randomSource, currentFireAge);
         this.trySpreadToAdjacent(serverLevel, blockPos.above(), 250 + biomeBurnoutEffect, randomSource, currentFireAge);
      }

      // Mechanism 2: 3D Air Block Ignition
      BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
      int startY = ENABLE_VERTICAL_FIRE_SPREAD ? -1 : 0;
      int endY = ENABLE_VERTICAL_FIRE_SPREAD ? (MAX_AGE / 10) : 0; // User: 4. Max Y spread based on max_age. Vanilla: 4 (max_age 15). 45/10 = 4. This matches original intent for range.

      for (int offsetX = -1; offsetX <= 1; ++offsetX) {
         for (int offsetZ = -1; offsetZ <= 1; ++offsetZ) {
            for (int offsetY = startY; offsetY <= endY; ++offsetY) {
               if (offsetX == 0 && offsetY == 0 && offsetZ == 0) continue; // Skip source block

               mutablePos.setWithOffset(blockPos, offsetX, offsetY, offsetZ);

               if (serverLevel.isEmptyBlock(mutablePos)) { // Only spread to air blocks
                  int igniteOddsFromNeighbors = this.getIgniteOddsFromNeighbors(serverLevel, mutablePos);
                  if (igniteOddsFromNeighbors <= 0) continue; // Target air block must be next to flammable material

                  int baseSpreadChanceDenominator = 100;
                  if (offsetY > 1) { // More difficult to spread far upwards
                     baseSpreadChanceDenominator += (offsetY - 1) * 100;
                  }

                  // User's scaled IGNITE_ODDS_ADJUSTED formula
                  int difficulty = serverLevel.getDifficulty().getId(); // 0:P, 1:E, 2:N, 3:H
                  int igniteChanceNumerator = ((igniteOddsFromNeighbors + 40 + difficulty * 7) * SCALE_AGE) / (currentFireAge + 30 * SCALE_AGE);

                  if (isIncreasedBurnoutBiome) {
                     igniteChanceNumerator /= 2;
                  }

                  if (igniteChanceNumerator > 0 &&
                          randomSource.nextInt(baseSpreadChanceDenominator) < igniteChanceNumerator && // Adjusted from <= to < to match K/N probability
                          (!serverLevel.isRaining() || !this.isNearRain(serverLevel, mutablePos))) {

                     // User's change: new fires start at age 0
                     serverLevel.setBlock(mutablePos, this.getStateWithAge(serverLevel, mutablePos, 0), Block.UPDATE_ALL);
                  }
               }
            }
         }
      }
   }

   protected boolean isNearRain(Level level, BlockPos pos) {
      return level.isRainingAt(pos) ||
              level.isRainingAt(pos.west()) ||
              level.isRainingAt(pos.east()) ||
              level.isRainingAt(pos.north()) ||
              level.isRainingAt(pos.south());
   }

   // Renamed from getBurnOdds to avoid confusion with the map field `burnOdds`
   private int getBlockBurnChance(BlockState targetBlockState) {
      if (targetBlockState.hasProperty(BlockStateProperties.WATERLOGGED) && targetBlockState.getValue(BlockStateProperties.WATERLOGGED)) {
         return 0;
      }
      return this.burnOdds.getInt(targetBlockState.getBlock()); // This is ADJACENT_BURN_ODDS from user code
   }

   // Renamed from getIgniteOdds1 to avoid confusion
   private int getBlockIgniteChance(BlockState neighborToAirBlockState) {
      if (neighborToAirBlockState.hasProperty(BlockStateProperties.WATERLOGGED) && neighborToAirBlockState.getValue(BlockStateProperties.WATERLOGGED)) {
         return 0;
      }
      return this.igniteOdds.getInt(neighborToAirBlockState.getBlock());
   }

   // Renamed from checkBurnOut
   private void trySpreadToAdjacent(Level level, BlockPos targetPos, int spreadChanceParam, RandomSource random, int sourceFireAge) {
      int targetBlockBurnChance = this.getBlockBurnChance(level.getBlockState(targetPos)); // ADJACENT_BURN_ODDS

      if (targetBlockBurnChance > 0 && random.nextInt(spreadChanceParam) < targetBlockBurnChance) {
         BlockState targetBlockState = level.getBlockState(targetPos);

         // User's scaled "set fire condition"
         // P_SetFireCond = (5 * SCALE_AGE) / (sourceFireAge + 10 * SCALE_AGE)
         boolean shouldIgnite = random.nextInt(sourceFireAge + 10 * SCALE_AGE) < (5 * SCALE_AGE);

         if (shouldIgnite && !level.isRainingAt(targetPos)) {
            // User's change: new fires start at age 0
            level.setBlock(targetPos, this.getStateWithAge(level, targetPos, 0), Block.UPDATE_ALL);
         } else {
            // User's change: Block is NOT removed if ignition fails.
            // Original vanilla code would have level.removeBlock(targetPos, false) here.
            // This makes fuel persist unless it actually catches fire.
         }

         if (targetBlockState.getBlock() instanceof TntBlock) {
            TntBlock.explode(level, targetPos);
         }
      }
   }

   private BlockState getStateWithAge(LevelAccessor levelAccessor, BlockPos blockPos, int fireAge) {
      BlockState blockState = levelAccessor.getBlockState(blockPos);
      // Ensure it's setting age on a fire block, or returning the default fire state if replacing another block
      if (blockState.is(this)) { // `this` refers to the FireBlock instance
         return blockState.setValue(AGE, Math.min(MAX_AGE, Math.max(0, fireAge)));
      }
      // If for some reason we are trying to set age on a non-fire block (e.g. newly placed fire), return default fire state with age.
      return this.defaultBlockState().setValue(AGE, Math.min(MAX_AGE, Math.max(0, fireAge)));
   }


   private boolean isValidFireLocation(BlockGetter blockGetter, BlockPos blockPos) {
      for (Direction direction : Direction.values()) {
         if (this.canBurn(blockGetter.getBlockState(blockPos.relative(direction)))) {
            return true;
         }
      }
      return false;
   }

   // Renamed from getIgniteOdds_Adjacent
   private int getIgniteOddsFromNeighbors(LevelReader levelReader, BlockPos airBlockPos) {
      // This method is called for an airBlockPos to see if its neighbors can ignite it.
      if (!levelReader.isEmptyBlock(airBlockPos)) { // Should already be an air block based on call site
         return 0;
      }
      int highestNeighborIgniteOdds = 0;
      for (Direction direction : Direction.values()) {
         BlockState neighborState = levelReader.getBlockState(airBlockPos.relative(direction));
         highestNeighborIgniteOdds = Math.max(highestNeighborIgniteOdds, this.getBlockIgniteChance(neighborState));
      }
      return highestNeighborIgniteOdds;
   }

   @Override
   protected boolean canBurn(BlockState potentialFuelState) {
      // A block can burn if it has a non-zero chance to ignite an adjacent air block (igniteOdds)
      // or a non-zero chance to be consumed/replaced by fire (burnOdds).
      // Vanilla uses getIgniteOdds1 (this.igniteOdds.getInt) > 0.
      // Using igniteOdds is consistent with vanilla logic for what can sustain fire.
      return this.getBlockIgniteChance(potentialFuelState) > 0;
   }

   private static int getFireTickDelay(RandomSource randomSource) {
      // User's change: fixed delay
      return 20; // 1 second (vanilla: 30-39 ticks, 1.5-1.95s)
   }

   @Override
   protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      builder.add(AGE, NORTH, EAST, SOUTH, WEST, UP);
   }

   /************************************************************************************
    * Part 5. Bootstrapping & Flammability Settings
    ************************************************************************************/
   private void setFlammable(Block block, int igniteOdds, int burnOdds) {
      // igniteOdds: How easily this block can cause an adjacent air block to catch fire (used in 3D spread for neighbors of air)
      // burnOdds: How easily this block burns up and is replaced by fire (used in direct adjacent spread)
      this.igniteOdds.put(block, igniteOdds);
      this.burnOdds.put(block, burnOdds);
   }

   public static void bootStrap() {
      FireBlock fireBlockInstance = (FireBlock) Blocks.FIRE;
      // --- PASTE YOUR FULL LIST OF setFlammable calls here ---
      fireBlockInstance.setFlammable(Blocks.OAK_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BIRCH_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.ACACIA_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.CHERRY_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_PLANKS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_MOSAIC, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BIRCH_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.ACACIA_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.CHERRY_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_MOSAIC_SLAB, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BIRCH_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.ACACIA_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.CHERRY_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_FENCE_GATE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BIRCH_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.ACACIA_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.CHERRY_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_FENCE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BIRCH_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.ACACIA_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.CHERRY_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_MOSAIC_STAIRS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.BIRCH_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.ACACIA_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.CHERRY_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.BAMBOO_BLOCK, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_OAK_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_SPRUCE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_BIRCH_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_JUNGLE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_ACACIA_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_CHERRY_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_DARK_OAK_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_MANGROVE_LOG, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_BAMBOO_BLOCK, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_OAK_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_SPRUCE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_BIRCH_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_JUNGLE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_ACACIA_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_CHERRY_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_DARK_OAK_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.STRIPPED_MANGROVE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.OAK_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.BIRCH_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.ACACIA_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.CHERRY_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_WOOD, 5, 5);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_ROOTS, 5, 20);
      fireBlockInstance.setFlammable(Blocks.OAK_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.SPRUCE_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BIRCH_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.JUNGLE_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.ACACIA_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.CHERRY_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.DARK_OAK_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.MANGROVE_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BOOKSHELF, 30, 20);
      fireBlockInstance.setFlammable(Blocks.TNT, 15, 100);
      fireBlockInstance.setFlammable(Blocks.GRASS, 60, 100);
      fireBlockInstance.setFlammable(Blocks.FERN, 60, 100);
      fireBlockInstance.setFlammable(Blocks.DEAD_BUSH, 60, 100);
      fireBlockInstance.setFlammable(Blocks.SUNFLOWER, 60, 100);
      fireBlockInstance.setFlammable(Blocks.LILAC, 60, 100);
      fireBlockInstance.setFlammable(Blocks.ROSE_BUSH, 60, 100);
      fireBlockInstance.setFlammable(Blocks.PEONY, 60, 100);
      fireBlockInstance.setFlammable(Blocks.TALL_GRASS, 60, 100);
      fireBlockInstance.setFlammable(Blocks.LARGE_FERN, 60, 100);
      fireBlockInstance.setFlammable(Blocks.DANDELION, 60, 100);
      fireBlockInstance.setFlammable(Blocks.POPPY, 60, 100);
      fireBlockInstance.setFlammable(Blocks.BLUE_ORCHID, 60, 100);
      fireBlockInstance.setFlammable(Blocks.ALLIUM, 60, 100);
      fireBlockInstance.setFlammable(Blocks.AZURE_BLUET, 60, 100);
      fireBlockInstance.setFlammable(Blocks.RED_TULIP, 60, 100);
      fireBlockInstance.setFlammable(Blocks.ORANGE_TULIP, 60, 100);
      fireBlockInstance.setFlammable(Blocks.WHITE_TULIP, 60, 100);
      fireBlockInstance.setFlammable(Blocks.PINK_TULIP, 60, 100);
      fireBlockInstance.setFlammable(Blocks.OXEYE_DAISY, 60, 100);
      fireBlockInstance.setFlammable(Blocks.CORNFLOWER, 60, 100);
      fireBlockInstance.setFlammable(Blocks.LILY_OF_THE_VALLEY, 60, 100);
      fireBlockInstance.setFlammable(Blocks.TORCHFLOWER, 60, 100);
      fireBlockInstance.setFlammable(Blocks.PITCHER_PLANT, 60, 100);
      fireBlockInstance.setFlammable(Blocks.WITHER_ROSE, 60, 100);
      fireBlockInstance.setFlammable(Blocks.PINK_PETALS, 60, 100);
      fireBlockInstance.setFlammable(Blocks.WHITE_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.ORANGE_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.MAGENTA_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.LIGHT_BLUE_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.YELLOW_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.LIME_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.PINK_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.GRAY_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.LIGHT_GRAY_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.CYAN_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.PURPLE_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BLUE_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BROWN_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.GREEN_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.RED_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BLACK_WOOL, 30, 60);
      fireBlockInstance.setFlammable(Blocks.VINE, 15, 100);
      fireBlockInstance.setFlammable(Blocks.COAL_BLOCK, 5, 5);
      fireBlockInstance.setFlammable(Blocks.HAY_BLOCK, 60, 20);
      fireBlockInstance.setFlammable(Blocks.TARGET, 15, 20);
      fireBlockInstance.setFlammable(Blocks.WHITE_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.ORANGE_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.MAGENTA_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.LIGHT_BLUE_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.YELLOW_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.LIME_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.PINK_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.GRAY_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.LIGHT_GRAY_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.CYAN_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.PURPLE_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.BLUE_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.BROWN_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.GREEN_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.RED_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.BLACK_CARPET, 60, 20);
      fireBlockInstance.setFlammable(Blocks.DRIED_KELP_BLOCK, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BAMBOO, 60, 60);
      fireBlockInstance.setFlammable(Blocks.SCAFFOLDING, 60, 60);
      fireBlockInstance.setFlammable(Blocks.LECTERN, 30, 20);
      fireBlockInstance.setFlammable(Blocks.COMPOSTER, 5, 20);
      fireBlockInstance.setFlammable(Blocks.SWEET_BERRY_BUSH, 60, 100);
      fireBlockInstance.setFlammable(Blocks.BEEHIVE, 5, 20);
      fireBlockInstance.setFlammable(Blocks.BEE_NEST, 30, 20);
      fireBlockInstance.setFlammable(Blocks.AZALEA_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.FLOWERING_AZALEA_LEAVES, 30, 60);
      fireBlockInstance.setFlammable(Blocks.CAVE_VINES, 15, 60);
      fireBlockInstance.setFlammable(Blocks.CAVE_VINES_PLANT, 15, 60);
      fireBlockInstance.setFlammable(Blocks.SPORE_BLOSSOM, 60, 100);
      fireBlockInstance.setFlammable(Blocks.AZALEA, 30, 60);
      fireBlockInstance.setFlammable(Blocks.FLOWERING_AZALEA, 30, 60);
      fireBlockInstance.setFlammable(Blocks.BIG_DRIPLEAF, 60, 100);
      fireBlockInstance.setFlammable(Blocks.BIG_DRIPLEAF_STEM, 60, 100);
      fireBlockInstance.setFlammable(Blocks.SMALL_DRIPLEAF, 60, 100);
      fireBlockInstance.setFlammable(Blocks.HANGING_ROOTS, 30, 60);
      fireBlockInstance.setFlammable(Blocks.GLOW_LICHEN, 15, 100);

   }
}
