/*
 * This is a DataObject that draws a curve through a series of data points that
 * are stored as a series of vertexes (not in arrays), allowing them to be edited.
 * New items can be added using the "add()" method (note, this may be a little
 * confusing in that the inherited class DraObject also has an "add" method,
 * however it takes a DrawObject as an argument so there is no conflict).  
 */
package mil.navy.usno.plotlib;

import java.util.Iterator;
import java.util.ArrayDeque;
import java.lang.Integer;

/**
 *
 * @author jspitzak
 */
public class Track2D extends DrawObject {
    
    /*
     * This object holds a series of child objects that build a newpath/vertexes/
     * strokepath structure.
     */
    public Track2D() {
        //  This object describes a path.  All child object use this path.
        this.newPath();
        //  This object holds all of the vertex data for drawing.
        _vertexes = new DrawObject();
        this.add( _vertexes );
        //  This object holds the original (unscaled) data.  Doesn't have to be
        //  a DrawObject, but using it makes things easy and straight-forward.
        _data = new DrawObject();
        //  This object is only used if the curve is to be "filled", i.e. colored
        //  to the minimum Y value.
        _fillPoints = new DrawObject();
        this.add( _fillPoints );
        //  The stroke object will be executed after the vertexes.
        _stroke = new DrawObject();
        _stroke.stroke();
        this.add( _stroke );      
        _saveXScale = 1.0;
        _saveYScale = 1.0;
        _types = new ArrayDeque<Integer>();
    }
    
    @Override
    public void scale( double newX, double newY ) {
        _saveXScale = newX;
        _saveYScale = newY;
        dataChange();
    }
    
    /*
     * Add a new data item.  This has to be added to both the vertexes and data
     * lists.  In the vertex list it is adjusted to the current scale.  There are
     * two types of points that can be added - normal and relative Normal points 
     * have scaled positions.  Relative positions are a number of pixels offset
     * from the previous position.
     */
    static final int NORMAL_POINT   = 0;
    static final int RELATIVE_POINT = 1;
    synchronized public void add( double x, double y, int type ) {
        DrawObject newData = new DrawObject();
        newData.vertex( x, y );
        _data.add( newData );
        newData = new DrawObject();
        //  If this is a "relative" point, give it the value of the previous
        //  point, offset by x and y.
        if ( type == RELATIVE_POINT ) {
            //  There must actually BE a previous point of course...
            if ( _vertexes.peekLast() != null )
                newData.vertex( _vertexes.peekLast()._x1 + x, _vertexes.peekLast()._y1 );
            //  If there isn't....well, this is as good as anything else we might do.
            else
                newData.vertex( x, y );
        }
        else
            newData.vertex( _saveXScale * x, _saveYScale * y );
        _vertexes.add( newData );
        _types.add( new Integer( type ) );
        //  Pop off the first value if we have exceded the size limit.
        if ( _sizeLimit > 0 ) {
            if ( _data.size() > _sizeLimit )
                _data.removeFirst();
            if ( _vertexes.size() > _sizeLimit )
                _vertexes.removeFirst();
        }
        _fillPoints.clear();
        //  Add the points required to fill the curve below, if that is requested.
        if ( _fillCurve ) {
            DrawObject newPoint1 = new DrawObject();
            newPoint1.vertex( _vertexes.peekLast()._x1, this._yOff );
            _fillPoints.add( newPoint1 );
            DrawObject newPoint2 = new DrawObject();
            newPoint2.vertex( _vertexes.peekFirst()._x1, this._yOff );
            _fillPoints.add( newPoint2 );
        }
    }
    
    /*
     * Default version of the "add()" function.
     */
    public void add( double x, double y ) {
        this.add( x, y, this.NORMAL_POINT );
    }
    
    /*
     * Override the clear function so that it zorches only the data.
     */
    @Override
    public void clear() {
        _data.clear();
        _vertexes.clear();
        _types.clear();
    }
    
    /*
     * Turn on/off filling of the plot area below the curve.  
     */
    public void fillCurve( boolean newVal ) {
        _fillCurve = newVal;
        if ( _fillCurve )
            _stroke.fill();
        else
            _stroke.stroke();
    }
    
    /*
     * Turn on/off line drawing.  If off, nothing will be drawn.  This is useful
     * for setting the current position.
     */
    public void draw( boolean newVal ) {
        if ( newVal ) {
            if ( _fillCurve )
                _stroke.fill();
            else
                _stroke.stroke();
        }
        else
            _stroke.empty();
    }
    
    /*
     * Set a limit on the number of data points that will be saved in the track.
     */
    public void sizeLimit( int newVal ) {
        _sizeLimit = newVal;
    }
    
    /*
     * Scale all of the vertex data by the current scale - this requires the
     * original data.
     */
    synchronized public void dataChange() {
        //synchronized( _data ) {
            //synchronized( _types ) {
                //synchronized( _vertexes ) {
                    Iterator<DrawObject> dataIter = _data.iterator();
                    Iterator<Integer> typeIter = _types.iterator();
                    DrawObject lastVertex = null;
                    for ( Iterator<DrawObject> iter = _vertexes.iterator(); iter.hasNext(); ) {
                        //try {
                            DrawObject data = dataIter.next();
                            DrawObject thisVertex = iter.next();
                            Integer type = typeIter.next();
                            if ( type == this.RELATIVE_POINT && lastVertex != null )
                                thisVertex.vertex( lastVertex._x1 + data._x1, lastVertex._y1 + data._y1 );
                            else
                                thisVertex.vertex( _saveXScale * data._x1, _saveYScale * data._y1 );
                            lastVertex = thisVertex;
                        //}
                        //catch ( java.util.NoSuchElementException e ) {
                        //}
                        //catch ( java.util.ConcurrentModificationException e ) {
                            //  No idea why this occasionally happens...have I failed to wrap
                            //  some operation in a "synchronized()"??
                        //}
                    }
                //}
            //}
        //}
    }
    
    protected DrawObject _vertexes;
    protected DrawObject _data;
    protected DrawObject _fillPoints;
    protected DrawObject _stroke;
    protected ArrayDeque<Integer> _types;
    protected double _saveXScale;
    protected double _saveYScale;
    protected int _sizeLimit;
    boolean _fillCurve;
    
}
