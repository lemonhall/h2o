package hex.nn;

import hex.FrameTask;
import water.Iced;
import water.MemoryManager;
import water.api.DocGen;
import water.api.Request.API;
import water.util.Utils;

import java.util.Arrays;
import java.util.Random;

import static hex.nn.NN.Loss;
import static hex.nn.NN.RNG.getRNG;

public abstract class Neurons extends Iced {
  static final int API_WEAVER = 1;
  public static DocGen.FieldDoc[] DOC_FIELDS;

  @API(help = "Number of neurons")
  protected int units;

  /**
   * Parameters (potentially different from the user input, can be modified here, e.g. rate)
   */
  protected NN params;

  /**
   * Layer state (one per neuron): activity, error
   */
  public transient double[] _a, _e;

  /**
   * References for feed-forward connectivity
   */
  public Neurons _previous; // previous layer of neurons
  protected NNModel.NNModelInfo _minfo; //reference to shared model info
  public float[] _w; //reference to _minfo.weights[layer] for convenience
  public float[] _wm; //reference to _minfo.weights_momenta[layer] for convenience
  public double[] _b; //reference to _minfo.biases[layer] for convenience
  public double[] _bm; //reference to _minfo.biases_momenta[layer] for convenience

  // Dropout (for input + hidden layers)
  transient Dropout dropout;
  Dropout createDropout(int units) {
    return new Dropout(units);
  }
  Neurons(int units) {
    this.units  = units;
  }

//  /**
//   * We need a way to encode a missing value in the neural net forward/back-propagation scheme.
//   * For simplicity and performance, we simply use the largest values to encode a missing value.
//   * If we run into exactly one of those values with regular neural net updates, then we're very
//   * likely also running into overflow problems, which will trigger a NaN somewhere, which will be
//   * caught and lead to automatic job cancellation.
//   */
//  public static final int missing_int_value = Integer.MAX_VALUE; //encode missing label or target
//  public static final double missing_double_value = Double.MAX_VALUE; //encode missing input

  /**
   * Helper class for dropout, only to be used from within a layer of neurons
   */
  private class Dropout {
    private transient Random _rand;
    private transient byte[] _bits;

    private Dropout(int units) {
      _bits = new byte[(units+7)/8];
      _rand = getRNG();
    }

    // for input layer
    private void clearSomeInput(Neurons previous) {
      assert(previous.isInput());
      final double rate = ((Input)previous)._dropout_rate;
      for( int i = 0; i < previous._a.length; i++ ) {
        if (_rand.nextFloat() < rate) previous._a[i] = 0;
      }
    }

    // for hidden layers
    private void fillBytes() {
      _rand.nextBytes(_bits);
    }

    private boolean unit_active(int o) {
      return (_bits[o / 8] & (1 << (o % 8))) != 0;
    }
  }

  public final void init(Neurons[] neurons, int index, NN p, NNModel.NNModelInfo minfo) {
    params = (NN)p.clone();
    params.rate *= Math.pow(params.rate_decay, index-1);
    _a = new double[units];
    if (!(this instanceof Output) && !(this instanceof Input)) {
      _e = new double[units];
    }
    if (!isInput()) {
      _previous = neurons[index-1]; //incoming neurons
      _minfo = minfo;
      _w = minfo.get_weights(index-1); //incoming weights
      _b = minfo.get_biases(index-1); //bias for this layer (starting at hidden layer)
      if (minfo.has_momenta()) {
        _wm = minfo.get_weights_momenta(index-1); //incoming weights
        _bm = minfo.get_biases_momenta(index-1); //bias for this layer (starting at hidden layer)
      }
    }
  }

  protected abstract void fprop(boolean training);

  protected abstract void bprop();

  boolean isInput() { return false; }

  /**
   * Apply gradient g to unit u with rate r and momentum m.
   */
  final void bprop(int u, double g, double r, double m) {
    double r2 = 0;
    final int off = u * _previous._a.length;
    for( int i = 0; i < _previous._a.length; i++ ) {
      int w = off + i;
      if( _previous._e != null )
        _previous._e[i] += g * _w[w];
      double d = g * _previous._a[i] - _w[w] * params.l2 - Math.signum(_w[w]) * params.l1;
      final double delta = r * d;

      // TODO finish per-weight acceleration, doesn't help for now
//        if( _wp != null && d != 0 ) {
//          boolean sign = _wp[w] >= 0;
//          double mult = Math.abs(_wp[w]);
//          // If the gradient kept its sign, increase
//          if( (d >= 0) == sign )
//            mult += .05f;
//          else {
//            if( mult > 1 )
//              mult *= .95f;
//            else
//              sign = !sign;
//          }
//          d *= mult;
//          _wp[w] = sign ? mult : -mult;
//        }

      _w[w] += delta;
      if( _wm != null ) {
        _w[w] += m * _wm[w];
        _wm[w] = (float)(delta);
      }
      if (params.max_w2 != Double.POSITIVE_INFINITY)
        r2 += _w[w] * _w[w];
    }
    if( params.max_w2 != Double.POSITIVE_INFINITY && r2 > params.max_w2 ) { // C.f. Improving neural networks by preventing co-adaptation of feature detectors
      final double scale = Math.sqrt(params.max_w2 / r2);
      for( int i = 0; i < _previous._a.length; i++ ) _w[off + i] *= scale;
    }
    final double delta = r * g;
    _b[u] += delta;
    if( _bm != null ) {
      _b[u] += m * _bm[u];
      _bm[u] = delta;
    }
  }

  public double rate(long n) {
    return params.rate / (1 + params.rate_annealing * n);
  }

  public double momentum(long n) {
    double m = params.momentum_start;
    if( params.momentum_ramp > 0 ) {
      if( n >= params.momentum_ramp )
        m = params.momentum_stable;
      else
        m += (params.momentum_stable - params.momentum_start) * n / params.momentum_ramp;
    }
    return m;
  }

  public static class Input extends Neurons {

    FrameTask.DataInfo _dinfo;
    int _numStart;

    public Input(int units, int numStart) {
      super(units);
      _numStart = numStart;
      _a = new double[units];
    }

    Input(int units, FrameTask.DataInfo d) {
      super(units);
      _dinfo = d;
      _numStart = _dinfo.numStart();
      _a = new double[units];
    }

    @Override protected void bprop() { throw new UnsupportedOperationException(); }
    @Override protected void fprop(boolean ignored) { throw new UnsupportedOperationException(); }

    @Override protected boolean isInput() {
      return true;
    }

    @API(help = "Dropout rate for the input layer")
    double _dropout_rate;


    public void setInput(final double[] data) {
      assert(_dinfo != null);
      double [] nums = MemoryManager.malloc8d(_dinfo._nums);
      int    [] cats = MemoryManager.malloc4(_dinfo._cats);
      int i = 0, ncats = 0;
      for(; i < _dinfo._cats; ++i){
        int c = (int)data[i];
        if(c != 0)cats[ncats++] = c + _dinfo._catOffsets[i] - 1;
      }
      final int n = data.length-_dinfo._responses;
      for(;i < n;++i){
        double d = data[i];
        if(_dinfo._normMul != null) d = (d - _dinfo._normSub[i-_dinfo._cats])*_dinfo._normMul[i-_dinfo._cats];
        nums[i-_dinfo._cats] = d;
      }
      setInput(nums, ncats, cats);
    }

    public void setInput(final double[] nums, final int numcat, final int[] cats) {
      Arrays.fill(_a, 0.);
      for (int i=0; i<numcat; ++i) _a[cats[i]] = 1.0;
      System.arraycopy(nums, 0, _a, _numStart, nums.length);
    }

  }

  public static class Tanh extends Neurons {
    public Tanh(int units) { super(units); }
    @Override protected void fprop(boolean training) {
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        final int off = o * _previous._a.length;
        if( !training || dropout == null || dropout.unit_active(o) ) {
          for( int i = 0; i < _previous._a.length; i++ ) {
            _a[o] += _w[off+i] * _previous._a[i];
          }
          _a[o] += _b[o];

          // tanh approx, slightly faster, untested
//          double a = Math.abs(_a[o]);
//          double b = 12 + a * (6 + a * (3 + a));
//          _a[o] = (_a[o] * b) / (a * b + 24);

          // use this identity: tanh = 2*sigmoid(2*x) - 1, evaluates faster than tanh(x)
          _a[o] = -1 + (2 / (1 + Math.exp(-2 * _a[o])));

//          _a[o] = Math.tanh(_a[o]); //slow
        }
      }
    }
    @Override protected void bprop() {
      long processed = _minfo.get_processed_total();
      double m = momentum(processed);
      double r = rate(processed);// * (1 - m);
      for( int u = 0; u < _a.length; u++ ) {
        // Gradient is error * derivative of hyperbolic tangent: (1 - x^2)
        double g = _e[u] * (1 - _a[u]) * (1 + _a[u]); //more numerically stable than 1-x^2
        bprop(u, g, r, m);
      }
    }
  }

  public static class TanhDropout extends Tanh {
    public TanhDropout(int units) {
      super(units);
    }
    @Override
    protected void fprop(boolean training) {
      if (training) {
        if (dropout == null) dropout = createDropout(units);
        dropout.fillBytes();
        if (_previous.isInput())
          dropout.clearSomeInput(_previous);
      }
      super.fprop(training);
      if (!training) Utils.div(_a, 2.f);
    }
  }

  public static class Maxout extends Neurons {
    public Maxout(int units) {
      super(units);
      dropout = createDropout(units);
    }

    @Override protected void fprop(boolean training) {
      if (dropout != null && training) {
        dropout.fillBytes();
        if (_previous.isInput())
          dropout.clearSomeInput(_previous);
      }

      double max = 0;
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        if( !training || dropout.unit_active(o) ) {
          final int off = o * _previous._a.length;
          _a[o] = Double.NEGATIVE_INFINITY;
          for( int i = 0; i < _previous._a.length; i++ )
            _a[o] = Math.max(_a[o], _w[off+i] * _previous._a[i]);
          _a[o] += _b[o];
          if( !training )
            _a[o] *= .5f;
          if( max < _a[o] )
            max = _a[o];
        }
      }
      if( max > 1 )
        for( int o = 0; o < _a.length; o++ )
          _a[o] /= max;
    }

    @Override protected void bprop() {
      long processed = _minfo.get_processed_total();
      double m = momentum(processed);
      double r = rate(processed);// * (1 - m);
      for( int u = 0; u < _a.length; u++ ) {
        double g = _e[u];
//                if( _a[o] < 0 )   Not sure if we should be using maxout with a hard zero bottom
//                    g = 0;
        bprop(u, g, r, m);
      }
    }
  }

  public static class Rectifier extends Neurons {

    public Rectifier(int units) {
      super(units);
      this.units = units;
    }

    @Override protected void fprop(boolean training) {
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        final int off = o * _previous._a.length;
        if( !training || dropout == null || dropout.unit_active(o) ) {
          for( int i = 0; i < _previous._a.length; i++ )
            _a[o] += _w[off+i] * _previous._a[i];
          _a[o] += _b[o];
          _a[o] = Math.max(_a[o], 0);
        }
      }
    }

    @Override protected void bprop() {
      long processed = _minfo.get_processed_total();
      final double m = momentum(processed);
      final double r = rate(processed);// * (1 - m);
      for( int u = 0; u < _a.length; u++ ) {
        //(d/dx)(max(0,x)) = 1 if x > 0, otherwise 0

        // no need to update the weights if there are no momenta and l1=0 and l2=0
        if (params.fast_mode || (_wm == null && params.l1 == 0.0 && params.l2 == 0.0)) { //correct
          if( _a[u] > 0 ) { // don't use >= (faster this way: lots of zeros)
            final double g = _e[u]; // * 1.0 (from derivative of rectifier)
            bprop(u, g, r, m);
          }
        }
        // if we have momenta or l1 or l2, then EVEN for g=0, there will be contributions to the weight updates
        // Note: this is slower than always doing the shortcut above, and might not affect the accuracy much
        else {
          final double g = _a[u] > 0 ? _e[u] : 0;
          bprop(u, g, r, m);
        }
      }
    }
  }

  public static class RectifierDropout extends Rectifier {
    public RectifierDropout(int units) {
      super(units);
    }

    @Override
    protected void fprop(boolean training) {
      if (training) {
        if (dropout == null) dropout = createDropout(units);
        dropout.fillBytes();
        if (_previous.isInput())
          dropout.clearSomeInput(_previous);
      }
      super.fprop(training);
      if (!training) Utils.div(_a, 2.f);
    }
  }

  public static abstract class Output extends Neurons {
    static final int API_WEAVER = 1;
    public static DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Loss function")
    public Loss loss = Loss.MeanSquare;

    Output(int units) { super(units); }

    protected abstract void fprop(); //don't differentiate between testing/training
    protected void fprop(boolean training) { throw new UnsupportedOperationException(); }
    protected void bprop() { throw new UnsupportedOperationException(); };
  }

  public static class Softmax extends Output {
    public Softmax(int units, Loss loss) {
      super(units);
      this.loss = loss;
    }

    @Override protected void fprop() {
      double max = Double.NEGATIVE_INFINITY;
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        final int off = o * _previous._a.length;
        for( int i = 0; i < _previous._a.length; i++ )
          _a[o] += _w[off+i] * _previous._a[i];
        _a[o] += _b[o];
        if( max < _a[o] )
          max = _a[o];
      }
      double scale = 0;
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = Math.exp(_a[o] - max);
        scale += _a[o];
      }
      for( int o = 0; o < _a.length; o++ )
        _a[o] /= scale;
    }
    protected void bprop(int target) {
      long processed = _minfo.get_processed_total();
      double m = momentum(processed);
      double r = rate(processed);// * (1 - m);
//      if (target == missing_int_value) return; //ignore missing response values
      for( int u = 0; u < _a.length; u++ ) {
        final double targetval = (u == target ? 1 : 0);
        double g = targetval - _a[u];
        if (loss == Loss.CrossEntropy) {
          //nothing else needed
        } else if (loss == Loss.MeanSquare) {
          g *= (1 - _a[u]) * _a[u];
        }
        bprop(u, g, r, m);
      }
    }
  }

  public static class Linear extends Output {
    public Linear(int units) { super(units); }
    @Override protected void fprop() {
      assert(_a.length == 1);
      int o = 0;
      _a[o] = 0;
      final int off = o * _previous._a.length;
      for( int i = 0; i < _previous._a.length; i++ )
        _a[o] += _w[off+i] * _previous._a[i];
      _a[o] += _b[o];
    }
    protected void bprop(double target) {
      long processed = _minfo.get_processed_total();
      double m = momentum(processed);
      double r = rate(processed);// * (1 - m);
      assert(loss == Loss.MeanSquare);
      int u = 0;
//      if (target == missing_double_value) return;
      double g = target - _a[u];
      g *= (1 - _a[u]) * _a[u];
      bprop(u, g, r, m);
    }
  }

  @Override public Neurons clone() {
    Neurons l = (Neurons) super.clone();
    if (dropout != null) l.dropout = new Dropout(units);
    return l;
  }

}