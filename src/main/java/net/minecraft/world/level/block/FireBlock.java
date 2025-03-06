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

/************************************************************************************
 * Initialisation
 ************************************************************************************/

/**
 * Section: Constants and Properties
 * This section defines constants and properties for the FireBlock class,
 * including age and directional properties.
 */
public class FireBlock extends BaseFireBlock {
   public static final int MAX_AGE = 15;
   public static final IntegerProperty AGE = BlockStateProperties.AGE_15;
   public static final BooleanProperty NORTH = PipeBlock.NORTH;
   public static final BooleanProperty EAST = PipeBlock.EAST;
   public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
   public static final BooleanProperty WEST = PipeBlock.WEST;
   public static final BooleanProperty UP = PipeBlock.UP;
   private static final Map<Direction, BooleanProperty> PROPERTY_BY_DIRECTION = PipeBlock.PROPERTY_BY_DIRECTION.entrySet().stream().filter((p_53467_) -> {
      return p_53467_.getKey() != Direction.DOWN;
   }).collect(Util.toMap());

   /**
    * Section: Voxel Shapes
    * Defines the voxel shapes for fire based on its directions.
    */
   private static final VoxelShape UP_AABB = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape WEST_AABB = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
   private static final VoxelShape EAST_AABB = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape NORTH_AABB = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
   private static final VoxelShape SOUTH_AABB = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
   private final Map<BlockState, VoxelShape> shapesCache;

   /**
    * Section: Ignition and Burn Constants
    * Defines various levels of ignition and burn rates.
    */
   private static final int IGNITE_INSTANT = 60;
   private static final int IGNITE_EASY = 30;
   private static final int IGNITE_MEDIUM = 15;
   private static final int IGNITE_HARD = 5;
   private static final int BURN_INSTANT = 100;
   private static final int BURN_EASY = 60;
   private static final int BURN_MEDIUM = 20;
   private static final int BURN_HARD = 5;
   private final Object2IntMap<Block> igniteOdds = new Object2IntOpenHashMap<>();
   private final Object2IntMap<Block> burnOdds = new Object2IntOpenHashMap<>();

   /**
    * Section: Constructor
    * Initializes default states and caches shapes for the FireBlock.
    */
   public FireBlock(BlockBehaviour.Properties p_53425_) {
      super(p_53425_, 1.0F);
      this.registerDefaultState(this.stateDefinition.any().setValue(AGE, Integer.valueOf(0)).setValue(NORTH, Boolean.valueOf(false)).setValue(EAST, Boolean.valueOf(false)).setValue(SOUTH, Boolean.valueOf(false)).setValue(WEST, Boolean.valueOf(false)).setValue(UP, Boolean.valueOf(false)));
      this.shapesCache = ImmutableMap.copyOf(this.stateDefinition.getPossibleStates().stream().filter((p_53497_) -> {
         return p_53497_.getValue(AGE) == 0;
      }).collect(Collectors.toMap(Function.identity(), FireBlock::calculateShape)));
   }

   /**
    * Section: Shape Calculation
    * Calculates the voxel shape based on the block state.
    */
   private static VoxelShape calculateShape(BlockState BLOCKSTATE) {
      VoxelShape voxelshape = Shapes.empty();
      if (BLOCKSTATE.getValue(UP)) {
         voxelshape = UP_AABB;
      }

      if (BLOCKSTATE.getValue(NORTH)) {
         voxelshape = Shapes.or(voxelshape, NORTH_AABB);
      }

      if (BLOCKSTATE.getValue(SOUTH)) {
         voxelshape = Shapes.or(voxelshape, SOUTH_AABB);
      }

      if (BLOCKSTATE.getValue(EAST)) {
         voxelshape = Shapes.or(voxelshape, EAST_AABB);
      }

      if (BLOCKSTATE.getValue(WEST)) {
         voxelshape = Shapes.or(voxelshape, WEST_AABB);
      }

      return voxelshape.isEmpty() ? DOWN_AABB : voxelshape;
   }

   /************************************************************************************
    * Functions for updating blocks
    ************************************************************************************/


   /**
    * Section: Block Updates
    * Handles block updates when adjacent blocks change.
    */
   public BlockState updateShape(BlockState p_53458_, Direction p_53459_, BlockState p_53460_, LevelAccessor p_53461_, BlockPos BLOCKPOSITION, BlockPos p_53463_) {
      return this.canSurvive(p_53458_, p_53461_, BLOCKPOSITION) ? this.getStateWithAge(p_53461_, BLOCKPOSITION, p_53458_.getValue(AGE)) : Blocks.AIR.defaultBlockState();
   }

   /**
    * Section: Shape Retrieval
    * Retrieves the shape of the fire block for rendering or collision.
    */
   public VoxelShape getShape(BlockState p_53474_, BlockGetter p_53475_, BlockPos BLOCKPOSITION, CollisionContext p_53477_) {
      return this.shapesCache.get(p_53474_.setValue(AGE, Integer.valueOf(0)));
   }

   /**
    * Section: Placement State
    * Determines the block state during placement.
    */
   public BlockState getStateForPlacement(BlockPlaceContext p_53427_) {
      return this.getStateForPlacement(p_53427_.getLevel(), p_53427_.getClickedPos());
   }

   protected BlockState getStateForPlacement(BlockGetter p_53471_, BlockPos BLOCKPOSITION) {
      BlockPos blockpos = BLOCKPOSITION.below();
      BlockState blockstate = p_53471_.getBlockState(blockpos);
      if (!this.canBurn(blockstate) && !blockstate.isFaceSturdy(p_53471_, blockpos, Direction.UP)) {
         BlockState blockstate1 = this.defaultBlockState();

         for(Direction direction : Direction.values()) {
            BooleanProperty booleanproperty = PROPERTY_BY_DIRECTION.get(direction);
            if (booleanproperty != null) {
               blockstate1 = blockstate1.setValue(booleanproperty, Boolean.valueOf(this.canBurn(p_53471_.getBlockState(BLOCKPOSITION.relative(direction)))));
            }
         }

         return blockstate1;
      } else {
         return this.defaultBlockState();
      }
   }

   /**
    * Section: Survival Check
    * Determines if the fire block can remain in its current position.
    */
   public boolean canSurvive(BlockState p_53454_, LevelReader LEVELREADER, BlockPos BLOCKPOSITION) {
      BlockPos blockpos = BLOCKPOSITION.below();
      return LEVELREADER.getBlockState(blockpos).isFaceSturdy(LEVELREADER, blockpos, Direction.UP) || this.isValidFireLocation(LEVELREADER, BLOCKPOSITION);
   }


   /************************************************************************************
    * Fire Behaviour and Logic
    ************************************************************************************/

   /**
    * Section: Fire Ticking
    * Implements the ticking behavior for fire blocks,
    * including spreading and
    * extinguishing.
    */
   public void tick(BlockState BLOCKSTATE, ServerLevel SERVERLEVEL, BlockPos BLOCKPOSITION, RandomSource RANDOMSOURCE) {
      SERVERLEVEL.scheduleTick(BLOCKPOSITION, this, getFireTickDelay(SERVERLEVEL.random));     /// 1. Schedule Next Tick
      if (SERVERLEVEL.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {            /// 2. Check Fire-Tick Game Rule
         if (!BLOCKSTATE.canSurvive(SERVERLEVEL, BLOCKPOSITION)) {                            /// 3. Check if Fire Can Survive (removes fire if not valid area via multiple checking functions to adjacent blocks)
            SERVERLEVEL.removeBlock(BLOCKPOSITION, false);
         }

         /**
          * 4. Check for Extinguishing Conditions
          */
         BlockState ADJACENTBLOCKSTATE = SERVERLEVEL.getBlockState(BLOCKPOSITION.below());           /// 4. Check for Extinguishing Conditions
         boolean IS_INFINITE_BURN_AREA = ADJACENTBLOCKSTATE.is(SERVERLEVEL.dimensionType().infiniburn());         /// (a) Determine if Block is in an "Infinite Burn" Area

         int FIREAGE = BLOCKSTATE.getValue(AGE);       /// NOTE: FIREAGE = AGE

         if (!IS_INFINITE_BURN_AREA && SERVERLEVEL.isRaining() && this.isNearRain(SERVERLEVEL, BLOCKPOSITION) && RANDOMSOURCE.nextFloat() < 0.2F + (float)FIREAGE * 0.03F) {
            SERVERLEVEL.removeBlock(BLOCKPOSITION, false);                                   /// (b) Extinguish Fire in Rain (Random chance based on the fire's AGE property.)
         } else {
            int UPDATED_FIREAGE = Math.min(15, FIREAGE + RANDOMSOURCE.nextInt(3) / 2);                        /// 5. Update Fire's Age
            if (FIREAGE != UPDATED_FIREAGE) {
               BLOCKSTATE = BLOCKSTATE.setValue(AGE, Integer.valueOf(UPDATED_FIREAGE));
               SERVERLEVEL.setBlock(BLOCKPOSITION, BLOCKSTATE, 4);
            }

            if (!IS_INFINITE_BURN_AREA) {                                                               /// 6. Validate Fire Location
               if (!this.isValidFireLocation(SERVERLEVEL, BLOCKPOSITION)) {                  /// (a) Check if Fire Location is Valid
                  BlockPos blockpos = BLOCKPOSITION.below();
                  if (!SERVERLEVEL.getBlockState(blockpos).isFaceSturdy(SERVERLEVEL, blockpos, Direction.UP) || FIREAGE > 3) {
                     SERVERLEVEL.removeBlock(BLOCKPOSITION, false);
                  }

                  return;
               }

               if (FIREAGE == 15 && RANDOMSOURCE.nextInt(4) == 0 && !this.canBurn(SERVERLEVEL.getBlockState(BLOCKPOSITION.below()))) {
                  SERVERLEVEL.removeBlock(BLOCKPOSITION, false);                             /// (b) Extinguish Fully Aged Fire (if at maximum age)
                  return;
               }
            }

            /**
             * 7. Spread Fire to Adjacent Blocks
             */
            boolean IS_INCREASED_BURNOUT_BIOME = SERVERLEVEL.getBiome(BLOCKPOSITION).is(BiomeTags.INCREASED_FIRE_BURNOUT);    /// (a) Check for Increased Burnout Biome (Wet or Humid Biomes)
            int BIOME_BURNOUT_EFFECT = IS_INCREASED_BURNOUT_BIOME ? -50 : 0;                           /// if in increased burnout biome, flag is true: so BIOME_BURNOUT_EFFECT = -50: Reduces the chance of fire spreading by increasing the burnout effect.

            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.east(), 300 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);        /// (b) Spread to Adjacent Blocks
            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.west(), 300 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);              /// see Section: Burn Check
            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.below(), 250 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);
            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.above(), 250 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);
            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.north(), 300 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);
            this.checkBurnOut(SERVERLEVEL, BLOCKPOSITION.south(), 300 + BIOME_BURNOUT_EFFECT, RANDOMSOURCE, FIREAGE);
            BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

            /**
             * three-dimensional spread of fire from the current fire block
             * where OFFSET_X, OFFSET_Z, and OFFSET_Y represent offsets in X, Z, and Y directions, respectively
             */
            for (int OFFSET_X = -1; OFFSET_X <= 1; ++OFFSET_X) {                     /// OFFSET_X (-1 to 1): X-axis (left/right relative to the fire block).
               for (int OFFSET_Z = -1; OFFSET_Z <= 1; ++OFFSET_Z) {               /// OFFSET_Z (-1 to 1): Z-axis (forward/backward relative to the fire block).
                  for (int OFFSET_Y = -1; OFFSET_Y <= 4; ++OFFSET_Y) {            /// OFFSET_Y (-1 to 4): Y-axis (up/down relative to the fire block; includes further upwards range).

                     if (OFFSET_X != 0 || OFFSET_Y != 0 || OFFSET_Z != 0) {          /// Exclude the Fire Block Itself
                        int BASE_SPREAD_CHANCE = 100;                             /// BASE_SPREAD_CHANCE is a base spread chance, starting at 100.
                        if (OFFSET_Y > 1) {
                           BASE_SPREAD_CHANCE += (OFFSET_Y - 1) * 100;                  /// For positions higher than 1 block above (OFFSET_Y > 1), the spread chance is reduced (higher BASE_SPREAD_CHANCE values make spread less likely).
                        }

                        blockpos$mutableblockpos.setWithOffset(BLOCKPOSITION, OFFSET_X, OFFSET_Y, OFFSET_Z);
                        int IGNITE_ODDS = this.getIgniteOdds_Adjacent(SERVERLEVEL, blockpos$mutableblockpos);
                        if (IGNITE_ODDS > 0) {                             /// Only flammable blocks (IGNITE_ODDS > 0) proceed further.
                           int IGNITE_ODDS_ADJUSTED = (IGNITE_ODDS + 40 + SERVERLEVEL.getDifficulty().getId() * 7) / (FIREAGE + 30);
                           if (IS_INCREASED_BURNOUT_BIOME) {                                    /// If IS_INCREASED_BURNOUT_BIOME (biome with INCREASED_FIRE_BURNOUT), the chance is halved
                              IGNITE_ODDS_ADJUSTED /= 2;
                           }

                           if (IGNITE_ODDS_ADJUSTED > 0 && RANDOMSOURCE.nextInt(BASE_SPREAD_CHANCE) <= IGNITE_ODDS_ADJUSTED && (!SERVERLEVEL.isRaining() || !this.isNearRain(SERVERLEVEL, blockpos$mutableblockpos))) {
                              int UPDATED_FIREAGE2 = Math.min(15, FIREAGE + RANDOMSOURCE.nextInt(5) / 4);
                              SERVERLEVEL.setBlock(blockpos$mutableblockpos, this.getStateWithAge(SERVERLEVEL, blockpos$mutableblockpos, UPDATED_FIREAGE2), 3);      /// Ignite the block with a fire block of age UPDATED_FIREAGE2
                           }
                        }
                     }
                  }
               }
            }

         }
      }
   }

   /**
    * Section: Rain Detection
    * Checks if the fire block is near rain, which can extinguish it.
    */
   protected boolean isNearRain(Level LEVEL, BlockPos BLOCKPOSITION) {
      return LEVEL.isRainingAt(BLOCKPOSITION) || LEVEL.isRainingAt(BLOCKPOSITION.west()) || LEVEL.isRainingAt(BLOCKPOSITION.east()) || LEVEL.isRainingAt(BLOCKPOSITION.north()) || LEVEL.isRainingAt(BLOCKPOSITION.south());
   }

   /**
    * Section: Burn and Ignite Odds
    * Retrieves the odds of a block burning.
    * Directly checks if the specific block represented by the BlockState has an ignition probability, returning 0 if the block is waterlogged.
    */
   private int getBurnOdds(BlockState BLOCKSTATE) {
      return BLOCKSTATE.hasProperty(BlockStateProperties.WATERLOGGED) &&
              BLOCKSTATE.getValue(BlockStateProperties.WATERLOGGED)
              ? 0
              : this.burnOdds.getInt(BLOCKSTATE.getBlock());
   }

   private int getIgniteOdds1(BlockState BLOCKSTATE_IGNITE) {   // if it is waterlogged it is not gonna ignite
      return BLOCKSTATE_IGNITE.hasProperty(BlockStateProperties.WATERLOGGED) &&
              BLOCKSTATE_IGNITE.getValue(BlockStateProperties.WATERLOGGED)
              ? 0
              : this.igniteOdds.getInt(BLOCKSTATE_IGNITE.getBlock());
   }

   /**
    * Section: Burn Check
    * Handles burning logic for nearby flammable blocks.
    * Related to section 7b
    */
   private void checkBurnOut(Level LEVEL, BlockPos ADJACENTBLOCKPOSITION, int SPREADCHANCE, RandomSource RANDOMSOURCE, int FIREAGE) {
      int ADJACENT_BURN_ODDS = this.getBurnOdds(LEVEL.getBlockState(ADJACENTBLOCKPOSITION));                             /// Check the Burn Odds of the Block
      if (RANDOMSOURCE.nextInt(SPREADCHANCE) < ADJACENT_BURN_ODDS) {                                                   /// Random Chance for Ignition

         // Check if the block is TNT first
         BlockState ADJACENTBLOCKSTATE = LEVEL.getBlockState(ADJACENTBLOCKPOSITION);


         // Handle fire ignition or removal
         if (RANDOMSOURCE.nextInt(FIREAGE + 10) < 5                /// The fire's age influences this randomness; older fire may spread more slowly.
                 && !LEVEL.isRainingAt(ADJACENTBLOCKPOSITION)) {          /// Prevents ignition if the block is exposed to rain.
            int UPDATED_FIREAGE = Math.min(FIREAGE + RANDOMSOURCE.nextInt(5) / 4, 15);       /// if logic above is true, Set Fire to the Block
            LEVEL.setBlock(ADJACENTBLOCKPOSITION, this.getStateWithAge(LEVEL, ADJACENTBLOCKPOSITION, UPDATED_FIREAGE), 3);
         } else {
            LEVEL.removeBlock(ADJACENTBLOCKPOSITION, false);              /// If the random chance fails or the block cannot ignite (e.g., due to rain), it is removed.
         }

         Block BLOCK = ADJACENTBLOCKSTATE.getBlock();
         if (BLOCK instanceof TntBlock) {                         ///  Special Case: TNT: If the block is TNT, it explodes when it burns, triggering its specific behavior
            TntBlock.explode(LEVEL, ADJACENTBLOCKPOSITION); // Exit the method since TNT explosion is handled
         }

      }
   }

   /**
    * Section: State Updates
    * Retrieves the updated block state based on its age.
    */
   private BlockState getStateWithAge(LevelAccessor LEVELACCESSOR, BlockPos BLOCKPOSITION, int FIREAGE) {
      BlockState BLOCKSTATE = getState(LEVELACCESSOR, BLOCKPOSITION);
      return BLOCKSTATE.is(Blocks.FIRE) ? BLOCKSTATE.setValue(AGE, Integer.valueOf(FIREAGE)) : BLOCKSTATE;
   }

   /**
    * Section: Fire Location Validation
    * Checks if the fire block is in a valid location to burn.
    */
   private boolean isValidFireLocation(BlockGetter BLOCKGETTER, BlockPos BLOCKPOSITION) {
      for (Direction direction : Direction.values()) {
         if (this.canBurn(BLOCKGETTER.getBlockState(BLOCKPOSITION.relative(direction)))) {
            return true;
         }
      }

      return false;
   }

   /**
    * Section: Ignition Odds Calculation
    * Calculates the odds of igniting nearby blocks based on their properties.
    * -
    * This checks all six adjacent positions (north, south, east, west, above, below)
    * for flammable blocks and returns the highest ignition odds among them.
    * If the position itself is not empty, it returns 0 (indicating no ignition is possible).
    */
   private int getIgniteOdds_Adjacent(LevelReader LEVELREADER, BlockPos BLOCKPOSITION) { // if it is an empty block it will look at its surrounding blocks
      if (!LEVELREADER.isEmptyBlock(BLOCKPOSITION)) {
         return 0;
      } else {
         int HIGHEST_IGNITION_ODDS = 0;

         for(Direction direction : Direction.values()) {
            BlockState ADJACENT_BLOCKSTATE = LEVELREADER.getBlockState(BLOCKPOSITION.relative(direction));
            HIGHEST_IGNITION_ODDS = Math.max(this.getIgniteOdds1(ADJACENT_BLOCKSTATE), HIGHEST_IGNITION_ODDS);
         }

         return HIGHEST_IGNITION_ODDS;
      }
   }

   /**
    * Section: Burn Check
    * Determines if the block is flammable and can burn.
    */
   protected boolean canBurn(BlockState BLOCKSTATE) {
      return this.getIgniteOdds1(BLOCKSTATE) > 0;
   }

   /**
    * Section: Placement Handling
    * Schedules the fire block to tick when it is placed.
    */
   public void onPlace(BlockState BLOCKSTATE_PLACEMENT, Level LEVEL, BlockPos BLOCKPOSITION_PLACEMENT, BlockState ADJACENT_BLOCKSTATE_PLACEMENT, boolean ISPLACEMENTVALID) {
      super.onPlace(BLOCKSTATE_PLACEMENT, LEVEL, BLOCKPOSITION_PLACEMENT, ADJACENT_BLOCKSTATE_PLACEMENT, ISPLACEMENTVALID);
      LEVEL.scheduleTick(BLOCKPOSITION_PLACEMENT, this, getFireTickDelay(LEVEL.random));
   }

   /**
    * Section: Fire Tick Delay
    * Determines the delay between fire block ticks.
    */
   private static int getFireTickDelay(RandomSource RANDOMSOURCE) {
      return 30 + RANDOMSOURCE.nextInt(10);
   }

   /**
    * Section: Block State Definition
    * Defines the possible states of the fire block.
    */
   protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> BLOCKSTATEDEFINITIONBUILDER) {
      BLOCKSTATEDEFINITIONBUILDER.add(AGE, NORTH, EAST, SOUTH, WEST, UP);
   }

   /************************************************************************************
    * Initialisation again
    ************************************************************************************/

   /**
    * Section: Flammability Settings
    * Configures the flammability properties for various blocks. using the section below this.
    * Essentially for block, add the ignite and burn odds as features
    */
   private void setFlammable(Block BLOCK, int IGNITE_ODDS, int BURN_ODDS) {
      this.igniteOdds.put(BLOCK, IGNITE_ODDS);
      this.burnOdds.put(BLOCK, BURN_ODDS);
   }

   /**
    * Section: Initialization
    * Sets the flammability of specific blocks when the FireBlock is loaded.
    * 446: ignite    447: burn
    */
   public static void bootStrap() {
      FireBlock fireblock = (FireBlock)Blocks.FIRE;
      fireblock.setFlammable(Blocks.OAK_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.SPRUCE_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.BIRCH_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.JUNGLE_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.ACACIA_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.CHERRY_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.DARK_OAK_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.MANGROVE_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_PLANKS, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_MOSAIC, 5, 20);
      fireblock.setFlammable(Blocks.OAK_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.SPRUCE_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.BIRCH_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.JUNGLE_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.ACACIA_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.CHERRY_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.DARK_OAK_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.MANGROVE_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_MOSAIC_SLAB, 5, 20);
      fireblock.setFlammable(Blocks.OAK_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.SPRUCE_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.BIRCH_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.JUNGLE_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.ACACIA_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.CHERRY_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.DARK_OAK_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.MANGROVE_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_FENCE_GATE, 5, 20);
      fireblock.setFlammable(Blocks.OAK_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.SPRUCE_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.BIRCH_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.JUNGLE_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.ACACIA_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.CHERRY_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.DARK_OAK_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.MANGROVE_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_FENCE, 5, 20);
      fireblock.setFlammable(Blocks.OAK_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.BIRCH_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.SPRUCE_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.JUNGLE_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.ACACIA_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.CHERRY_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.DARK_OAK_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.MANGROVE_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.BAMBOO_MOSAIC_STAIRS, 5, 20);
      fireblock.setFlammable(Blocks.OAK_LOG, 5, 5);
      fireblock.setFlammable(Blocks.SPRUCE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.BIRCH_LOG, 5, 5);
      fireblock.setFlammable(Blocks.JUNGLE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.ACACIA_LOG, 5, 5);
      fireblock.setFlammable(Blocks.CHERRY_LOG, 5, 5);
      fireblock.setFlammable(Blocks.DARK_OAK_LOG, 5, 5);
      fireblock.setFlammable(Blocks.MANGROVE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.BAMBOO_BLOCK, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_OAK_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_SPRUCE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_BIRCH_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_JUNGLE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_ACACIA_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_CHERRY_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_DARK_OAK_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_MANGROVE_LOG, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_BAMBOO_BLOCK, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_OAK_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_SPRUCE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_BIRCH_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_JUNGLE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_ACACIA_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_CHERRY_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_DARK_OAK_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.STRIPPED_MANGROVE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.OAK_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.SPRUCE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.BIRCH_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.JUNGLE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.ACACIA_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.CHERRY_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.DARK_OAK_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.MANGROVE_WOOD, 5, 5);
      fireblock.setFlammable(Blocks.MANGROVE_ROOTS, 5, 20);
      fireblock.setFlammable(Blocks.OAK_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.SPRUCE_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.BIRCH_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.JUNGLE_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.ACACIA_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.CHERRY_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.DARK_OAK_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.MANGROVE_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.BOOKSHELF, 30, 20);
      fireblock.setFlammable(Blocks.TNT, 15, 100);
      fireblock.setFlammable(Blocks.GRASS, 60, 100);
      fireblock.setFlammable(Blocks.FERN, 60, 100);
      fireblock.setFlammable(Blocks.DEAD_BUSH, 60, 100);
      fireblock.setFlammable(Blocks.SUNFLOWER, 60, 100);
      fireblock.setFlammable(Blocks.LILAC, 60, 100);
      fireblock.setFlammable(Blocks.ROSE_BUSH, 60, 100);
      fireblock.setFlammable(Blocks.PEONY, 60, 100);
      fireblock.setFlammable(Blocks.TALL_GRASS, 60, 100);
      fireblock.setFlammable(Blocks.LARGE_FERN, 60, 100);
      fireblock.setFlammable(Blocks.DANDELION, 60, 100);
      fireblock.setFlammable(Blocks.POPPY, 60, 100);
      fireblock.setFlammable(Blocks.BLUE_ORCHID, 60, 100);
      fireblock.setFlammable(Blocks.ALLIUM, 60, 100);
      fireblock.setFlammable(Blocks.AZURE_BLUET, 60, 100);
      fireblock.setFlammable(Blocks.RED_TULIP, 60, 100);
      fireblock.setFlammable(Blocks.ORANGE_TULIP, 60, 100);
      fireblock.setFlammable(Blocks.WHITE_TULIP, 60, 100);
      fireblock.setFlammable(Blocks.PINK_TULIP, 60, 100);
      fireblock.setFlammable(Blocks.OXEYE_DAISY, 60, 100);
      fireblock.setFlammable(Blocks.CORNFLOWER, 60, 100);
      fireblock.setFlammable(Blocks.LILY_OF_THE_VALLEY, 60, 100);
      fireblock.setFlammable(Blocks.TORCHFLOWER, 60, 100);
      fireblock.setFlammable(Blocks.PITCHER_PLANT, 60, 100);
      fireblock.setFlammable(Blocks.WITHER_ROSE, 60, 100);
      fireblock.setFlammable(Blocks.PINK_PETALS, 60, 100);
      fireblock.setFlammable(Blocks.WHITE_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.ORANGE_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.MAGENTA_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.LIGHT_BLUE_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.YELLOW_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.LIME_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.PINK_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.GRAY_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.LIGHT_GRAY_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.CYAN_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.PURPLE_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.BLUE_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.BROWN_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.GREEN_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.RED_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.BLACK_WOOL, 30, 60);
      fireblock.setFlammable(Blocks.VINE, 15, 100);
      fireblock.setFlammable(Blocks.COAL_BLOCK, 5, 5);
      fireblock.setFlammable(Blocks.HAY_BLOCK, 60, 20);
      fireblock.setFlammable(Blocks.TARGET, 15, 20);
      fireblock.setFlammable(Blocks.WHITE_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.ORANGE_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.MAGENTA_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.LIGHT_BLUE_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.YELLOW_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.LIME_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.PINK_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.GRAY_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.LIGHT_GRAY_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.CYAN_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.PURPLE_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.BLUE_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.BROWN_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.GREEN_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.RED_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.BLACK_CARPET, 60, 20);
      fireblock.setFlammable(Blocks.DRIED_KELP_BLOCK, 30, 60);
      fireblock.setFlammable(Blocks.BAMBOO, 60, 60);
      fireblock.setFlammable(Blocks.SCAFFOLDING, 60, 60);
      fireblock.setFlammable(Blocks.LECTERN, 30, 20);
      fireblock.setFlammable(Blocks.COMPOSTER, 5, 20);
      fireblock.setFlammable(Blocks.SWEET_BERRY_BUSH, 60, 100);
      fireblock.setFlammable(Blocks.BEEHIVE, 5, 20);
      fireblock.setFlammable(Blocks.BEE_NEST, 30, 20);
      fireblock.setFlammable(Blocks.AZALEA_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.FLOWERING_AZALEA_LEAVES, 30, 60);
      fireblock.setFlammable(Blocks.CAVE_VINES, 15, 60);
      fireblock.setFlammable(Blocks.CAVE_VINES_PLANT, 15, 60);
      fireblock.setFlammable(Blocks.SPORE_BLOSSOM, 60, 100);
      fireblock.setFlammable(Blocks.AZALEA, 30, 60);
      fireblock.setFlammable(Blocks.FLOWERING_AZALEA, 30, 60);
      fireblock.setFlammable(Blocks.BIG_DRIPLEAF, 60, 100);
      fireblock.setFlammable(Blocks.BIG_DRIPLEAF_STEM, 60, 100);
      fireblock.setFlammable(Blocks.SMALL_DRIPLEAF, 60, 100);
      fireblock.setFlammable(Blocks.HANGING_ROOTS, 30, 60);
      fireblock.setFlammable(Blocks.GLOW_LICHEN, 15, 100);
   }
}