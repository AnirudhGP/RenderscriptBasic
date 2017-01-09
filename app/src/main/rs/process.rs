#pragma version(1)
#pragma rs java_package_name(com.intel.sample.androidbasicrs)
#pragma rs_fp_relaxed


int radiusHi;
int radiusLo;
int xTouchApply;
int yTouchApply;
rs_allocation input;
int width;
int height;

const float4 gWhite = {1.f, 1.f, 1.f, 1.f};
const float3 channelWeights = {0.299f, 0.587f, 0.114f};
const int xindices[] = {1, 0, 0, -1, -1, 1, -1, 1, 2, 0, 0, -2, -2, 2, -2, 2, 3, 0, 0, -3, -3, 3, -3, 3};
const int yindices[] = {0, 1, -1, 0, -1, 1, 1, -1, 0, 2, -2, 0, -2, 2, 2, -2, 0, 3, -3, 0, -3, 3, 3, -3} ;
uchar4 __attribute__((kernel)) root(const uchar4 in, uint32_t x, uint32_t y)
{
    float4 f4 = rsUnpackColor8888(in);
    int xRel = x - xTouchApply;
    int yRel = y - yTouchApply;
    int polar = xRel*xRel + yRel*yRel;
    uchar4 out;

    if(polar > radiusHi || polar < radiusLo)
    {
        if(polar < radiusLo)
        {
            //rsDebug("MEANBLUR122 : " ,f4.r ,f4.g, f4.b);
            float3 outPixel = dot(f4.rgb, channelWeights);
            float3 neighbors;
            float sumX = outPixel.x, sumY = outPixel.y, sumZ = outPixel.z;
            if(x>2 && x<(width-1) && y>2 && y<(height-1)) {
                for(int i=0; i<24; i++) {
                    neighbors = dot(rsUnpackColor8888(rsGetElementAt_uchar4(input, x + xindices[i], y + yindices[i])).rgb, channelWeights);
                    sumX += (neighbors.x);
                    sumY += (neighbors.y);
                    sumZ += (neighbors.z);
                }
            }
            sumX /= 25;
            sumY /= 25;
            sumZ /= 25;
            outPixel.x = sumX;
            outPixel.y = sumY;
            outPixel.z = sumZ;

            //rsDebug("MEANBLUR2 : " ,outPixel.r ,outPixel.g, outPixel.b);
            out = rsPackColorTo8888(outPixel);
        }
        else
        {
            out = in;
        }
    }
    else
    {
         out = rsPackColorTo8888(gWhite);
    }
    return out;
}